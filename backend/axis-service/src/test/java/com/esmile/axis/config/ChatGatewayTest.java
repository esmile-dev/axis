package com.esmile.axis.config;

import com.esmile.axis.config.ChatGateway.LlmOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ChatGateway}：超时透传 OpenAiChatOptions、system 仅在非空白时设置、
 * memoryConversationId 非空时挂 MessageChatMemoryAdvisor 并注入 CONVERSATION_ID、
 * 结构化输出 null 兜底 IllegalStateException、流式 token 透传。
 * 这是全仓库唯一 mock ChatClient 流式链的测试。
 */
@ExtendWith(MockitoExtension.class)
class ChatGatewayTest {

    @Mock
    private AiConfigService aiConfigService;
    @Mock
    private ChatMemory chatMemory;
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
        gateway = new ChatGateway(aiConfigService, chatMemory);
    }

    /** 基础链路：prompt() → user() → options()；system/advisors 按需在各用例单独 stub。 */
    private void stubBaseChain() {
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
    }

    /** 捕获传给 options(...) 的 OpenAiChatOptions.Builder 并取出 timeout。 */
    private Duration capturedTimeout() {
        ArgumentCaptor<OpenAiChatOptions.Builder> captor = ArgumentCaptor.forClass(OpenAiChatOptions.Builder.class);
        verify(spec).options(captor.capture());
        return captor.getValue().build().getTimeout();
    }

    @Test
    void defaultOptions_60sTimeoutWithoutMemory() {
        assertThat(LlmOptions.DEFAULT.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(LlmOptions.DEFAULT.memoryConversationId()).isNull();
    }

    @Test
    void call_passesUserPromptWithDefaultTimeout() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("答");

        String out = gateway.call("用户问题", LlmOptions.DEFAULT);

        assertThat(out).isEqualTo("答");
        verify(spec).user("用户问题");
        assertThat(capturedTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void call_customTimeout_honored() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("答");

        gateway.call("问题", new LlmOptions(Duration.ofSeconds(30), null));

        assertThat(capturedTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void stream_systemNullOrBlank_omitsSystemPrompt() {
        stubBaseChain();
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("答"));

        gateway.stream(null, "问题", LlmOptions.DEFAULT).collectList().block();
        gateway.stream("  ", "问题", LlmOptions.DEFAULT).collectList().block();

        verify(spec, never()).system(any(String.class));
    }

    @Test
    void call_withoutConversationId_attachesNoAdvisor() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("答");

        gateway.call("问题", LlmOptions.DEFAULT);

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
        when(streamSpec.content()).thenReturn(Flux.just("先", "后"));

        Flux<String> out = gateway.stream("系统提示", "问题", new LlmOptions(Duration.ofSeconds(60), "knowledge-i1"));

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
        doReturn(entity).when(callSpec).entity(Out.class);

        assertThat(gateway.callEntity("问题", Out.class, LlmOptions.DEFAULT)).isSameAs(entity);
    }

    @Test
    void structuredCall_nullEntity_throwsIllegalState() {
        stubBaseChain();
        when(spec.call()).thenReturn(callSpec);
        doReturn(null).when(callSpec).entity(any(Class.class));

        assertThatThrownBy(() -> gateway.callEntity("问题", Object.class, LlmOptions.DEFAULT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LLM returned empty structured output");
    }
}
