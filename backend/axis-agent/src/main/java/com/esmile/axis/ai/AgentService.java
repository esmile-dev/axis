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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.stream.Collectors;

/**
 * Agent 对话编排 — system prompt 拼装（含长期记忆注入）、
 * advisor/tools 装配、流式事件产生。传输层（SSE 帧）由 Controller 负责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AiConfigService aiConfigService;
    private final ChatMemory chatMemory;
    private final InboxTool inboxTool;
    private final IssueTool issueTool;
    private final ProjectTool projectTool;
    private final KnowledgeTool knowledgeTool;
    private final MemoryTool memoryTool;
    private final ToolCallNotifier toolCallNotifier;
    private final ConfirmationService confirmationService;
    private final ChatHistoryService chatHistoryService;
    private final ConversationSummaryService conversationSummaryService;
    private final LlmCallLogger llmCallLogger;

    public Flux<ChatEvent> chat(String message, String sessionId) {
        return chat(message, sessionId, false);
    }

    /**
     * 流式对话：合并 token 流（Spring AI）与工具事件流（ToolCallNotifier），
     * 产出 {@link ChatEvent} 序列，尾部带 {@link ChatEvent.Done}。
     * retry=true（手动重试）时先去掉失败时已落库的同一 user 消息，避免历史双份。
     */
    public Flux<ChatEvent> chat(String message, String sessionId, boolean retry) {
        if (retry) {
            chatHistoryService.removeLastUserMessageIfMatches(sessionId, message);
        }
        // 超窗预压缩：最旧一批进会话摘要（失败静默退化为硬截断），需在 advisor 落 user 消息前执行
        conversationSummaryService.compressIfNeeded(sessionId);
        boolean newConversation = !chatHistoryService.conversationExists(sessionId);
        Sinks.Many<ChatEvent> toolEvents = toolCallNotifier.begin();

        LlmCallTracker tracker = llmCallLogger.start(LlmFeature.AGENT_CHAT);
        Flux<ChatEvent> tokenFlux = chatClient().prompt()
                .system(systemPrompt(sessionId))
                .user(message)
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                .tools(inboxTool, issueTool, projectTool, knowledgeTool, memoryTool)
                .stream()
                .chatResponse()
                .doOnNext(cr -> tracker.captureUsage(cr.getMetadata().getUsage()))
                // usage-only 末帧没有内容（choices 为空），getResult() 为 null，滤掉
                .mapNotNull(cr -> cr.getResult() != null ? cr.getResult().getOutput().getText() : null)
                .<ChatEvent>map(ChatEvent.Token::new)
                .concatWith(Flux.just(new ChatEvent.Done()))
                .doOnComplete(tracker::success)
                .doOnError(tracker::error)
                .doFinally(signalType -> {
                    toolCallNotifier.end();
                    // 断连/正常结束时取消挂起中的人工确认（按拒绝放行，不遗留悬挂线程）
                    confirmationService.rejectAllPending();
                    // 新会话：首轮结束后异步用 LLM 生成语义标题（覆盖截断兜底，失败静默）
                    if (newConversation) {
                        chatHistoryService.generateAndUpgradeTitle(sessionId, message);
                    }
                })
                // LLM 流失败不裸断：映射成 error 帧 + done 帧，前端可展示可收尾
                .onErrorResume(e -> {
                    log.warn("agent.chat.failed session={} reason={}", sessionId, e.toString());
                    String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return Flux.just(new ChatEvent.Error("生成失败：" + detail), new ChatEvent.Done());
                });

        return Flux.merge(tokenFlux, toolEvents.asFlux());
    }

    /** 非流式对话：与 {@link #chat} 同一套编排，改走 {@code .call()} */
    public String chatSync(String message, String sessionId) {
        conversationSummaryService.compressIfNeeded(sessionId);
        boolean newConversation = !chatHistoryService.conversationExists(sessionId);
        LlmCallTracker tracker = llmCallLogger.start(LlmFeature.AGENT_CHAT);
        String content;
        try {
            ChatResponse response = chatClient().prompt()
                    .system(systemPrompt(sessionId))
                    .user(message)
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .tools(inboxTool, issueTool, projectTool, knowledgeTool, memoryTool)
                    .call()
                    .chatResponse();
            tracker.captureUsage(response.getMetadata().getUsage());
            tracker.success();
            content = response.getResult().getOutput().getText();
        } catch (Exception e) {
            tracker.error(e);
            throw e;
        }
        if (newConversation) {
            chatHistoryService.generateAndUpgradeTitle(sessionId, message);
        }
        return content;
    }

    /** PRD 扩写：纯文本流，可搜索知识库获取上下文 */
    public Flux<String> expandPrd(String title) {
        String prompt = String.format("""
                请为以下功能需求生成一份简洁的 PRD（产品需求文档），使用 Markdown 格式。

                需求标题：%s

                PRD 应包含：
                1. 目标与背景
                2. 用户故事（JTBD 格式）
                3. 技术方案概要
                4. 验收标准

                保持简洁可执行。
                """, title);

        LlmCallTracker tracker = llmCallLogger.start(LlmFeature.PRD_EXPAND);
        return chatClient().prompt()
                .user(prompt)
                .tools(knowledgeTool)
                .stream()
                .chatResponse()
                .doOnNext(cr -> tracker.captureUsage(cr.getMetadata().getUsage()))
                .mapNotNull(cr -> cr.getResult() != null ? cr.getResult().getOutput().getText() : null)
                .doOnComplete(tracker::success)
                .doOnError(tracker::error);
    }

    /** 基础 prompt + 注入长期记忆（最多 50 条）+ 该会话早期对话的滚动摘要（有则注入） */
    private String systemPrompt(String sessionId) {
        String base = """
                你是 AI Station 的智能助手。你可以帮助用户管理他们的个人工作站，包括：
                - Inbox（灵感回收站）：快速记录碎片想法
                - Projects（项目管理）：管理项目
                - Issues（任务管理）：创建和管理任务、需求
                - Knowledge（知识库）：沉淀技术知识和经验

                你拥有操作这些模块的工具，可以根据用户的自然语言指令执行相应操作。
                回答时使用简洁的中文，操作完成后简要确认结果。

                你还有长期记忆能力：当用户表达值得跨会话记住的偏好、习惯或重要事实（或明确要求“记住”）时，
                调用 saveMemory 保存；可用 listMemories / deleteMemory 管理已有记忆。
                """;
        String memories = chatHistoryService.listMemories().stream()
                .map(m -> "- " + m.getContent())
                .collect(Collectors.joining("\n"));
        String prompt = memories.isBlank() ? base : base + "\n以下是你已记住的长期记忆：\n" + memories;
        return conversationSummaryService.findSummary(sessionId)
                .map(summary -> prompt + "\n以下是本次会话早期对话的摘要（仅供参考，近期完整对话见上下文）：\n" + summary)
                .orElse(prompt);
    }

    private ChatClient chatClient() {
        return aiConfigService.get();
    }
}
