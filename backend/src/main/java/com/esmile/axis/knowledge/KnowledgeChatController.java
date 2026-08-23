package com.esmile.axis.knowledge;

import com.esmile.axis.knowledge.chat.KnowledgeAskService;
import com.esmile.axis.knowledge.chat.KnowledgeQaService;
import com.esmile.axis.knowledge.dto.KnowledgeAskRequest;
import com.esmile.axis.knowledge.dto.KnowledgeChatRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 知识库问答 API — SSE 流式输出（FR-009 单条目问答 + knowledge-ask-rag 跨条目问答）。
 * 只做传输层职责：接收请求、委托 Service、把结果映射成 SSE 帧。
 *
 * <p>SSE 帧协议与 /api/agent/chat 一致：{@code {"type":"token","text":"..."}} 文本增量、
 * {@code {"type":"done"}} 结束；/ask 在 token 前多发一帧
 * {@code {"type":"sources","json":"[{n,itemId,title}]"}} 引用来源。问答不挂工具，无 tool 帧；
 * 错误处理沿用 /api/agent/chat 现状（无专门错误帧，前端按请求失败兜底）。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeChatController {

    private final KnowledgeQaService knowledgeQaService;
    private final KnowledgeAskService knowledgeAskService;

    @PostMapping(value = "/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, String>>> chat(
            @PathVariable String id, @Valid @RequestBody KnowledgeChatRequest request) {
        return knowledgeQaService.chat(id, request.message())
                .map(token -> ServerSentEvent.<Map<String, String>>builder(
                        Map.of("type", "token", "text", token)).build())
                .concatWith(Flux.just(ServerSentEvent.<Map<String, String>>builder(
                        Map.of("type", "done")).build()));
    }

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, String>>> ask(@Valid @RequestBody KnowledgeAskRequest request) {
        KnowledgeAskService.AskResult result = knowledgeAskService.ask(request.question());
        return Flux.concat(
                Flux.just(frame(Map.of("type", "sources", "json", JsonMapper.shared().writeValueAsString(result.sources())))),
                result.answer().map(token -> frame(Map.of("type", "token", "text", token))),
                Flux.just(frame(Map.of("type", "done"))));
    }

    private static ServerSentEvent<Map<String, String>> frame(Map<String, String> data) {
        return ServerSentEvent.<Map<String, String>>builder(data).build();
    }
}
