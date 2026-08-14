package com.esmile.axis.config;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
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
 * （{@link AiConfigService#get()}）、{@link OpenAiChatOptions} 超时构造、以及
 * {@link MessageChatMemoryAdvisor} 的挂载（{@code memoryConversationId} 非空时）。
 *
 * <p>不做重试：异常原样抛给调用方，由各调用方自己的 try/catch 兜底。
 */
@Service
@RequiredArgsConstructor
public class ChatGateway {

    private final AiConfigService aiConfigService;
    private final ChatMemory chatMemory;

    /**
     * 单次调用的选项：超时 + 可选的会话记忆 id。
     * {@code memoryConversationId} 非空时挂 {@link MessageChatMemoryAdvisor}
     * 并注入 {@link ChatMemory#CONVERSATION_ID}。
     */
    public record LlmOptions(Duration timeout, String memoryConversationId) {
        public static final LlmOptions DEFAULT = new LlmOptions(Duration.ofSeconds(60), null);
    }

    /** 同步文本生成（user prompt only；需要 system prompt 的场景走 {@link #stream}）。 */
    public String call(String user, LlmOptions options) {
        return request(null, user, options).call().content();
    }

    /** 结构化输出。entity 为 null 视为无效输出，抛 {@link IllegalStateException}。 */
    public <T> T callEntity(String user, Class<T> type, LlmOptions options) {
        T result = request(null, user, options).call().entity(type);
        if (result == null) {
            throw new IllegalStateException("LLM returned empty structured output");
        }
        return result;
    }

    /** 流式文本生成。{@code system} 为 null 或空白时不设置 system prompt。 */
    public Flux<String> stream(String system, String user, LlmOptions options) {
        return request(system, user, options).stream().content();
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
