package com.esmile.axis.ai;

import com.esmile.axis.ai.tool.InboxTool;
import com.esmile.axis.ai.tool.IssueTool;
import com.esmile.axis.ai.tool.KnowledgeTool;
import com.esmile.axis.ai.tool.MemoryTool;
import com.esmile.axis.ai.tool.ProjectTool;
import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.llm.LlmCallLogger;
import com.esmile.axis.llm.LlmCallTracker;
import com.esmile.axis.llm.LlmFeature;
import com.esmile.axis.service.ChatHistoryService;
import com.esmile.axis.service.ConversationSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentService#chat} 事件流：正常路径 token→done；
 * LLM 流失败 → error 帧 + done（不裸断流），且确认门兜底放行仍执行。
 * 每次对话经 {@link LlmCallLogger} 记录为一次 AGENT_CHAT 调用。
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private AiConfigService aiConfigService;
    @Mock
    private ChatMemory chatMemory;
    @Mock
    private InboxTool inboxTool;
    @Mock
    private IssueTool issueTool;
    @Mock
    private ProjectTool projectTool;
    @Mock
    private KnowledgeTool knowledgeTool;
    @Mock
    private MemoryTool memoryTool;
    @Mock
    private ConfirmationService confirmationService;
    @Mock
    private ChatHistoryService chatHistoryService;
    @Mock
    private ConversationSummaryService conversationSummaryService;
    @Mock
    private LlmCallLogger llmCallLogger;
    @Mock
    private LlmCallTracker tracker;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec spec;
    @Mock
    private ChatClient.StreamResponseSpec streamSpec;

    private AgentService service;

    @BeforeEach
    void setUp() {
        service = new AgentService(aiConfigService, chatMemory, inboxTool, issueTool, projectTool,
                knowledgeTool, memoryTool, new ToolCallNotifier(), confirmationService, chatHistoryService,
                conversationSummaryService, llmCallLogger);
        lenient().when(llmCallLogger.start(any())).thenReturn(tracker);
        lenient().when(conversationSummaryService.findSummary(anyString())).thenReturn(Optional.empty());
    }

    private static ChatResponse resp(String text, Usage usage) {
        ChatResponseMetadata metadata = usage != null
                ? ChatResponseMetadata.builder().usage(usage).build()
                : ChatResponseMetadata.builder().build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    /** OpenAI 流式末帧：只带 usage 没有内容（choices 为空）。 */
    private static ChatResponse usageOnlyFrame(Usage usage) {
        return new ChatResponse(List.of(), ChatResponseMetadata.builder().usage(usage).build());
    }

    /** 链路 mock 对齐 ChatGatewayTest 用法；会话视为已存在（跳过标题生成分支）。 */
    private void stubChatChain(Flux<ChatResponse> responses) {
        when(chatHistoryService.conversationExists(anyString())).thenReturn(true);
        when(chatHistoryService.listMemories()).thenReturn(List.of());
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(Advisor.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.tools(any(Object[].class))).thenReturn(spec);
        when(spec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(responses);
    }

    @Test
    void chat_llmStreamCompletes_emitsTokensThenDone() {
        stubChatChain(Flux.just(resp("你", null), resp("好", null)));

        List<ChatEvent> events = service.chat("问题", "s1").collectList().block();

        assertThat(events).containsExactly(new ChatEvent.Token("你"), new ChatEvent.Token("好"), new ChatEvent.Done());
    }

    @Test
    void chat_llmStreamFails_emitsErrorFrameThenDone_andRejectsPendingConfirmations() {
        stubChatChain(Flux.error(new RuntimeException("API key 无效")));

        List<ChatEvent> events = service.chat("问题", "s1").collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events).first().isInstanceOfSatisfying(ChatEvent.Error.class,
                e -> assertThat(e.message()).contains("API key 无效"));
        assertThat(events).last().isEqualTo(new ChatEvent.Done());
        verify(confirmationService).rejectAllPending();
    }

    @Test
    void chat_retryTrue_removesDuplicateUserMessageBeforeStreaming() {
        stubChatChain(Flux.just(resp("答", null)));

        List<ChatEvent> events = service.chat("同一句话", "s1", true).collectList().block();

        // 流式失败时 advisor 已把 user 消息落库，重试需先按内容去重再发
        verify(chatHistoryService).removeLastUserMessageIfMatches("s1", "同一句话");
        assertThat(events).containsExactly(new ChatEvent.Token("答"), new ChatEvent.Done());
    }

    @Test
    void chat_completes_recordsAgentChatSuccessWithLastFrameUsage() {
        Usage usage = new DefaultUsage(100, 20, 120);
        stubChatChain(Flux.just(resp("答", null), usageOnlyFrame(usage)));

        List<ChatEvent> events = service.chat("问题", "s1").collectList().block();

        // usage-only 末帧不产生 token
        assertThat(events).containsExactly(new ChatEvent.Token("答"), new ChatEvent.Done());
        verify(llmCallLogger).start(LlmFeature.AGENT_CHAT);
        verify(tracker).captureUsage(usage);
        verify(tracker).success();
        verify(tracker, never()).error(any());
    }

    @Test
    void chat_llmStreamFails_recordsErrorBeforeErrorFrame() {
        RuntimeException boom = new RuntimeException("API key 无效");
        stubChatChain(Flux.error(boom));

        service.chat("问题", "s1").collectList().block();

        verify(tracker).error(boom);
        verify(tracker, never()).success();
    }

    @Test
    void chat_anyRequest_triggersCompressionCheckBeforeStreaming() {
        stubChatChain(Flux.just(resp("答", null)));

        service.chat("问题", "s1").collectList().block();

        // advisor 在流式开始前就落 user 消息，压缩检查必须先发生
        verify(conversationSummaryService).compressIfNeeded("s1");
    }

    @Test
    void chat_summaryPresent_injectedIntoSystemPrompt() {
        when(conversationSummaryService.findSummary("s1")).thenReturn(Optional.of("早期摘要：用户调研过 RAG"));
        stubChatChain(Flux.just(resp("答", null)));

        service.chat("问题", "s1").collectList().block();

        verify(spec).system(argThat((String s) -> s.contains("早期摘要：用户调研过 RAG")));
    }
}
