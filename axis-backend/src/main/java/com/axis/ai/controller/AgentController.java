package com.axis.ai.controller;

import com.axis.ai.tool.InboxTool;
import com.axis.ai.tool.IssueTool;
import com.axis.ai.tool.KnowledgeTool;
import com.axis.ai.tool.ProjectTool;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Agent 对话 API — SSE 流式输出
 * 接收用户自然语言指令，通过 Spring AI ChatClient + Tools 执行操作
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final ChatClient chatClient;
    private final org.springframework.ai.chat.memory.ChatMemory chatMemory;
    private final InboxTool inboxTool;
    private final IssueTool issueTool;
    private final ProjectTool projectTool;
    private final KnowledgeTool knowledgeTool;

    /**
     * 流式对话：用户发消息，Agent 通过 Tool Calling 操作系统并流式回复
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String sessionId = body.getOrDefault("sessionId", "default-session");

        return chatClient.prompt()
                .user(message)
                .advisors(org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(advisorSpec -> advisorSpec.param("chat_memory_conversation_id", sessionId))
                .tools(inboxTool, issueTool, projectTool, knowledgeTool)
                .stream()
                .content();
    }

    /**
     * 非流式对话：简单请求-响应模式
     */
    @PostMapping("/chat/sync")
    public Map<String, String> chatSync(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String sessionId = body.getOrDefault("sessionId", "default-session");

        String response = chatClient.prompt()
                .user(message)
                .advisors(org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(advisorSpec -> advisorSpec.param("chat_memory_conversation_id", sessionId))
                .tools(inboxTool, issueTool, projectTool, knowledgeTool)
                .call()
                .content();

        return Map.of("response", response);
    }

    /**
     * PRD 扩写 Agent — 替代原来的 /api/ai/expand
     * 流式输出，根据标题生成 PRD
     */
    @PostMapping(value = "/expand", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> expandPrd(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "Unknown Task");

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

        return chatClient.prompt()
                .user(prompt)
                .tools(knowledgeTool) // 可搜索知识库获取上下文
                .stream()
                .content();
    }
}
