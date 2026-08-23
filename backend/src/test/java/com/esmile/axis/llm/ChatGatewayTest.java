package com.esmile.axis.llm;

import com.esmile.axis.llm.ChatGateway.LlmOptions;
import com.esmile.axis.llm.LlmCallLogger;
import com.esmile.axis.llm.LlmCallTracker;
import com.esmile.axis.llm.LlmFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link ChatGateway}：超时透传 OpenAiChatOptions、system 仅在非空白时设置、
 * memoryConversationId 非空时挂 MessageChatMemoryAdvisor 并注入 CONVERSATION_ID、
 * 结构化输出 null 兜底 IllegalStateException、流式 token 透传、
 * 每次调用经 {@link LlmCallLogger} 记录（成功带 usage / 异常记 error 后原样抛出）。
 * 这是全仓库唯一 mock ChatClient 流式链的测试。
 */
@ExtendWith(MockitoExtension.class)
class ChatGatewayTest {

    @Mock
    private AiConfigService aiConfigService;
    @Mock
    private ChatMemory chatMemory;
    @Mock
    private LlmCallLogger llmCallLogger;
    @Mock
    private LlmCallTracker tracker;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec spec;
    @Mock
    private ChatClient.CallResponseSpec callSpec;
    @Mock
    private ChatClient.StreamResponseSpec streamSpec;

    private ChatGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new ChatGateway(aiConfigService, chatMemory, llmCallLogger);
        lenient().when(llmCallLogger.start(any())).thenReturn(tracker);
    }

    /** 基础链路：prompt() → user() → options()；system/advisors 按需在各用例单独 stub。 */
    private void stubBaseChain() {
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
    }

    private static ChatResponse chatResponse(String text, Usage usage) {
        ChatResponseMetadata metadata = usage != null
                ? ChatResponseMetadata.builder().usage(usage).build()
                : ChatResponseMetadata.builder().build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    /** 捕获传给 options(...) 的 OpenAiChatOptions.Builder 并取出 timeout。 */
    private Duration capturedTimeout() {
        ArgumentCaptor<OpenAiChatOptions.Builder> captor = ArgumentCaptor.forClass(OpenAiChatOptions.Builder.class);
        verify(spec).options(captor.capture());
        return captor.getValue().build().getTimeout();
    }

    @Test
    void featureOnlyOptions_60sTimeoutWithoutMemory() {
        LlmOptions options = new LlmOptions(LlmFeature.TITLE_GEN);
        assertThat(options.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(options.memoryConversationId()).isNull();
    }

    @Test
    void call_passesUserPromptWithDefaultTimeout() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse("答", null));

        String out = gateway.call("用户问题", new LlmOptions(LlmFeature.TITLE_GEN));

        assertThat(out).isEqualTo("答");
        verify(spec).user("用户问题");
        assertThat(capturedTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void call_customTimeout_honored() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse("答", null));

        gateway.call("问题", new LlmOptions(Duration.ofSeconds(30), null, LlmFeature.DIGEST_SUMMARY));

        assertThat(capturedTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void stream_systemNullOrBlank_omitsSystemPrompt() {
        stubBaseChain();
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(chatResponse("答", null)));

        gateway.stream(null, "问题", new LlmOptions(LlmFeature.KNOWLEDGE_QA)).collectList().block();
        gateway.stream("  ", "问题", new LlmOptions(LlmFeature.KNOWLEDGE_QA)).collectList().block();

        verify(spec, never()).system(any(String.class));
    }

    @Test
    void call_withoutConversationId_attachesNoAdvisor() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse("答", null));

        gateway.call("问题", new LlmOptions(LlmFeature.TITLE_GEN));

        verify(spec, never()).advisors(any(Advisor.class));
        verify(spec, never()).advisors(any(Consumer.class));
    }

    @Test
    void stream_withConversationId_attachesMemoryAdvisorAndConversationIdParam() {
        stubBaseChain();
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(Advisor.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(chatResponse("先", null), chatResponse("后", null)));

        Flux<String> out = gateway.stream("系统提示", "问题",
                new LlmOptions(Duration.ofSeconds(60), "knowledge-i1", LlmFeature.KNOWLEDGE_ASK));

        assertThat(out.collectList().block()).containsExactly("先", "后");
        ArgumentCaptor<Advisor> advisorCaptor = ArgumentCaptor.forClass(Advisor.class);
        verify(spec).advisors(advisorCaptor.capture());
        assertThat(advisorCaptor.getValue()).isInstanceOf(MessageChatMemoryAdvisor.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(spec).advisors(consumerCaptor.capture());
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        consumerCaptor.getValue().accept(advisorSpec);
        verify(advisorSpec).param(ChatMemory.CONVERSATION_ID, "knowledge-i1");
    }

    @Test
    void structuredCall_returnsEntity() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        record Out(String v) {
        }
        Out entity = new Out("x");
        when(callSpec.responseEntity(Out.class))
                .thenReturn(new ResponseEntity<>(chatResponse("{\"v\":\"x\"}", null), entity));

        assertThat(gateway.callEntity("问题", Out.class, new LlmOptions(LlmFeature.RERANK))).isSameAs(entity);
    }

    @Test
    void structuredCall_nullEntity_throwsIllegalState() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.responseEntity(any(Class.class)))
                .thenReturn(new ResponseEntity<>(chatResponse("null", null), null));

        assertThatThrownBy(() -> gateway.callEntity("问题", Object.class, new LlmOptions(LlmFeature.RERANK)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LLM returned empty structured output");
    }

    // ---------- 可观测性：每次调用经 LlmCallLogger 记录 ----------

    @Test
    void call_success_recordsUsageAndFeature() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        Usage usage = new DefaultUsage(10, 5, 15);
        when(callSpec.chatResponse()).thenReturn(chatResponse("答", usage));

        gateway.call("问题", new LlmOptions(LlmFeature.TITLE_GEN));

        verify(llmCallLogger).start(LlmFeature.TITLE_GEN);
        verify(tracker).captureUsage(usage);
        verify(tracker).success();
        verify(tracker, never()).error(any());
    }

    @Test
    void call_llmThrows_recordsErrorAndRethrows() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        RuntimeException boom = new RuntimeException("timeout");
        when(callSpec.chatResponse()).thenThrow(boom);

        assertThatThrownBy(() -> gateway.call("问题", new LlmOptions(LlmFeature.TITLE_GEN)))
                .isSameAs(boom);
        verify(tracker).error(boom);
        verify(tracker, never()).success();
    }

    @Test
    void stream_capturesUsageFromFramesAndRecordsSuccessOnComplete() {
        stubBaseChain();
        when(spec.stream()).thenReturn(streamSpec);
        Usage usage = new DefaultUsage(100, 20, 120);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(
                chatResponse("先", null), chatResponse("后", usage)));

        List<String> out = gateway.stream(null, "问题", new LlmOptions(LlmFeature.KNOWLEDGE_QA))
                .collectList().block();

        assertThat(out).containsExactly("先", "后");
        verify(tracker).captureUsage(usage);
        verify(tracker).success();
        verify(tracker, never()).error(any());
    }

    @Test
    void stream_llmFails_recordsErrorAndPropagates() {
        stubBaseChain();
        when(spec.stream()).thenReturn(streamSpec);
        RuntimeException boom = new RuntimeException("stream broken");
        when(streamSpec.chatResponse()).thenReturn(Flux.error(boom));

        assertThatThrownBy(() -> gateway.stream(null, "问题", new LlmOptions(LlmFeature.KNOWLEDGE_QA))
                .collectList().block());
        verify(tracker).error(boom);
        verify(tracker, never()).success();
    }

    @Test
    void structuredCall_success_recordsUsageWithFeature() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        record Out(String v) {
        }
        Usage usage = new DefaultUsage(7, 3, 10);
        when(callSpec.responseEntity(eq(Out.class)))
                .thenReturn(new ResponseEntity<>(chatResponse("{\"v\":\"x\"}", usage), new Out("x")));

        gateway.callEntity("问题", Out.class, new LlmOptions(LlmFeature.RERANK));

        verify(llmCallLogger).start(LlmFeature.RERANK);
        verify(tracker).captureUsage(usage);
        verify(tracker).success();
    }
}
