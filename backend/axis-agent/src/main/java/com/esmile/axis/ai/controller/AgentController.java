package com.esmile.axis.ai.controller;

import com.esmile.axis.ai.AgentService;
import com.esmile.axis.ai.ChatEvent;
import com.esmile.axis.ai.ConfirmationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Agent 对话 API — SSE 流式输出
 * 只做传输层职责：接收请求、委托 AgentService、把 ChatEvent 映射成 SSE 帧。
 *
 * <p>SSE 每帧为 JSON：{@code {"type":"token","text":"..."}} 文本增量、
 * {@code {"type":"tool","label":"..."}} 工具调用事件、
 * {@code {"type":"confirm","confirmId":...,"action":...,"detail":...}} 危险操作确认请求、
 * {@code {"type":"error","message":"..."}} LLM 流失败（后随 done 帧）、
 * {@code {"type":"done"}} 结束。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final ConfirmationService confirmationService;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, String>>> chat(@Valid @RequestBody ChatRequest request) {
        return agentService.chat(request.message(), request.sessionIdOrDefault(), request.retryOrDefault())
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
     * 危险操作人工确认回调 — 前端确认卡片点击后调用，
     * 放行（或拒绝）挂起中的删除类 tool；确认 id 未知/已过期返回 404
     */
    @PostMapping("/confirm/{confirmId}")
    public ResponseEntity<Map<String, String>> confirm(@PathVariable String confirmId,
                                                       @Valid @RequestBody ConfirmRequest request) {
        return confirmationService.resolve(confirmId, request.approved())
                ? ResponseEntity.ok(Map.of("status", "ok"))
                : ResponseEntity.notFound().build();
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
            case ChatEvent.Confirm(String confirmId, String action, String detail) ->
                    Map.of("type", "confirm", "confirmId", confirmId, "action", action, "detail", detail);
            case ChatEvent.Error(String message) -> Map.of("type", "error", "message", message);
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

    /**
     * /api/agent/confirm/{confirmId} 的请求体
     */
    public record ConfirmRequest(@NotNull Boolean approved) {
    }
}
