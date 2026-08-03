package com.esmile.axis.ai.controller;

import com.esmile.axis.ai.AgentService;
import com.esmile.axis.ai.ChatEvent;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Agent 对话 API — SSE 流式输出
 * 只做传输层职责：接收请求、委托 AgentService、把 ChatEvent 映射成 SSE 帧。
 *
 * <p>SSE 每帧为 JSON：{@code {"type":"token","text":"..."}} 文本增量、
 * {@code {"type":"tool","label":"..."}} 工具调用事件、{@code {"type":"done"}} 结束。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, String>>> chat(@Valid @RequestBody ChatRequest request) {
        return agentService.chat(request.message(), request.sessionIdOrDefault())
                .map(AgentController::toSse);
    }

    /**
     * 非流式对话：简单请求-响应模式
     */
    @PostMapping("/chat/sync")
    public Map<String, String> chatSync(@Valid @RequestBody ChatRequest request) {
        String response = agentService.chatSync(request.message(), request.sessionIdOrDefault());
        return Map.of("response", response);
    }

    /**
     * PRD 扩写 Agent — 替代原来的 /api/ai/expand
     * 流式输出，根据标题生成 PRD
     */
    @PostMapping(value = "/expand", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> expandPrd(@Valid @RequestBody ExpandRequest request) {
        return agentService.expandPrd(request.titleOrDefault());
    }

    private static ServerSentEvent<Map<String, String>> toSse(ChatEvent event) {
        Map<String, String> payload = switch (event) {
            case ChatEvent.Token(String text) -> Map.of("type", "token", "text", text);
            case ChatEvent.Tool(String label) -> Map.of("type", "tool", "label", label);
            case ChatEvent.Done() -> Map.of("type", "done");
        };
        return ServerSentEvent.<Map<String, String>>builder(payload).build();
    }

    /**
     * /api/agent/expand 的请求体
     */
    public record ExpandRequest(String title) {

        private static final String DEFAULT_TITLE = "Unknown Task";

        public String titleOrDefault() {
            return title == null || title.isBlank() ? DEFAULT_TITLE : title;
        }
    }
}
