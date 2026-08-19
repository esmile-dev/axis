package com.esmile.axis.config;

import com.esmile.axis.llm.LlmCallLogger;
import com.esmile.axis.llm.LlmCallTracker;
import com.esmile.axis.llm.LlmFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Objects;

/**
 * 全应用调用 chat 模型的唯一入口（参见 CONTEXT.md 的 ChatGateway 词条）。
 *
 * <p>需要 LLM 生成（同步文本、流式、结构化输出）的代码都经由这里发起调用，不再各自
 * 组装 {@code aiConfigService.get().prompt()…} 链。本类统一拥有：ChatClient 获取
 * （{@link AiConfigService#get()}）、{@link OpenAiChatOptions} 超时构造、
 * {@link MessageChatMemoryAdvisor} 的挂载（{@code memoryConversationId} 非空时）、
 * 以及每次调用的可观测性记录（{@link LlmCallLogger}：耗时 / token usage / 成败，
 * 流式的 usage 在末帧捕获）。
 *
 * <p>不做重试：异常原样抛给调用方，由各调用方自己的 try/catch 兜底。
 */
@Service
@RequiredArgsConstructor
public class ChatGateway {

    private final AiConfigService aiConfigService;
    private final ChatMemory chatMemory;
    private final LlmCallLogger llmCallLogger;

    /**
     * 单次调用的选项：超时 + 可选的会话记忆 id + 业务来源（feature，用于调用日志）。
     * {@code memoryConversationId} 非空时挂 {@link MessageChatMemoryAdvisor}
     * 并注入 {@link ChatMemory#CONVERSATION_ID}。
     */
    public record LlmOptions(Duration timeout, String memoryConversationId, LlmFeature feature) {
        /** 默认 60s 超时、无会话记忆。 */
        public LlmOptions(LlmFeature feature) {
            this(Duration.ofSeconds(60), null, feature);
        }

        /** 默认 60s 超时、带会话记忆。 */
        public LlmOptions(String memoryConversationId, LlmFeature feature) {
            this(Duration.ofSeconds(60), memoryConversationId, feature);
        }
    }

    /** 同步文本生成（user prompt only；需要 system prompt 的场景走 {@link #stream}）。 */
    public String call(String user, LlmOptions options) {
        LlmCallTracker tracker = llmCallLogger.start(options.feature());
        try {
            ChatResponse response = request(null, user, options).call().chatResponse();
            tracker.captureUsage(response.getMetadata().getUsage());
            tracker.success();
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            tracker.error(e);
            throw e;
        }
    }

    /** 结构化输出。entity 为 null 视为无效输出，抛 {@link IllegalStateException}。 */
    public <T> T callEntity(String user, Class<T> type, LlmOptions options) {
        LlmCallTracker tracker = llmCallLogger.start(options.feature());
        try {
            ResponseEntity<ChatResponse, T> response = request(null, user, options).call().responseEntity(type);
            tracker.captureUsage(response.response().getMetadata().getUsage());
            tracker.success();
            T result = response.entity();
            if (result == null) {
                throw new IllegalStateException("LLM returned empty structured output");
            }
            return result;
        } catch (Exception e) {
            tracker.error(e);
            throw e;
        }
    }

    /** 流式文本生成。{@code system} 为 null 或空白时不设置 system prompt。 */
    public Flux<String> stream(String system, String user, LlmOptions options) {
        LlmCallTracker tracker = llmCallLogger.start(options.feature());
        return request(system, user, options).stream().chatResponse()
                // usage 只在末帧返回（OpenAI include_usage 默认开启）；中间帧为 null，tracker 只保留最后一个非 null
                .doOnNext(cr -> tracker.captureUsage(cr.getMetadata().getUsage()))
                // 末帧只带 usage 没有内容（choices 为空），getResult() 为 null，需滤掉
                .mapNotNull(cr -> cr.getResult() != null ? cr.getResult().getOutput().getText() : null)
                .doOnComplete(tracker::success)
                .doOnError(tracker::error);
    }

    private ChatClient.ChatClientRequestSpec request(String system, String user, LlmOptions options) {
        Objects.requireNonNull(options, "options");
        ChatClient.ChatClientRequestSpec spec = aiConfigService.get().prompt();
        if (system != null && !system.isBlank()) {
            spec = spec.system(system);
        }
        spec = spec.user(user);
        if (options.memoryConversationId() != null) {
            spec = spec.advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, options.memoryConversationId()));
        }
        return spec.options(OpenAiChatOptions.builder().timeout(options.timeout()));
    }
}
