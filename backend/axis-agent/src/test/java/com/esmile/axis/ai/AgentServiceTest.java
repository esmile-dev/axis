package com.esmile.axis.ai;

import com.esmile.axis.ai.tool.InboxTool;
import com.esmile.axis.ai.tool.IssueTool;
import com.esmile.axis.ai.tool.KnowledgeTool;
import com.esmile.axis.ai.tool.MemoryTool;
import com.esmile.axis.ai.tool.ProjectTool;
import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.service.ChatHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentService#chat} 事件流：正常路径 token→done；
 * LLM 流失败 → error 帧 + done（不裸断流），且确认门兜底放行仍执行。
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
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec spec;
    @Mock
    private ChatClient.StreamResponseSpec streamSpec;

    private AgentService service;

    @BeforeEach
    void setUp() {
        service = new AgentService(aiConfigService, chatMemory, inboxTool, issueTool, projectTool,
                knowledgeTool, memoryTool, new ToolCallNotifier(), confirmationService, chatHistoryService);
    }

    /** 链路 mock 对齐 ChatGatewayTest 用法；会话视为已存在（跳过标题生成分支）。 */
    private void stubChatChain(Flux<String> content) {
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
        when(streamSpec.content()).thenReturn(content);
    }

    @Test
    void chat_llmStreamCompletes_emitsTokensThenDone() {
        stubChatChain(Flux.just("你", "好"));

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
        stubChatChain(Flux.just("答"));

        List<ChatEvent> events = service.chat("同一句话", "s1", true).collectList().block();

        // 流式失败时 advisor 已把 user 消息落库，重试需先按内容去重再发
        verify(chatHistoryService).removeLastUserMessageIfMatches("s1", "同一句话");
        assertThat(events).containsExactly(new ChatEvent.Token("答"), new ChatEvent.Done());
    }
}
