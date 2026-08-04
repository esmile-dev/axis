package com.esmile.axis.knowledge.controller;

import com.esmile.axis.knowledge.chat.KnowledgeQaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 知识条目问答 API — SSE 流式输出（FR-009）。
 * 只做传输层职责：接收请求、委托 KnowledgeQaService、把 token 映射成 SSE 帧。
 *
 * <p>SSE 帧协议与 /api/agent/chat 一致：{@code {"type":"token","text":"..."}} 文本增量、
 * {@code {"type":"done"}} 结束。问答不挂工具，无 tool 帧；错误处理沿用 /api/agent/chat
 * 现状（无专门错误帧，前端按请求失败兜底）。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeChatController {

    private final KnowledgeQaService knowledgeQaService;

    @PostMapping(value = "/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, String>>> chat(
            @PathVariable String id, @Valid @RequestBody KnowledgeChatRequest request) {
        return knowledgeQaService.chat(id, request.message())
                .map(token -> ServerSentEvent.<Map<String, String>>builder(
                        Map.of("type", "token", "text", token)).build())
                .concatWith(Flux.just(ServerSentEvent.<Map<String, String>>builder(
                        Map.of("type", "done")).build()));
    }
}
