package com.esmile.axis.knowledge.chat;

import com.esmile.axis.config.ChatGateway;
import com.esmile.axis.config.ChatGateway.LlmOptions;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import com.esmile.axis.knowledge.search.KnowledgeSearchHit;
import com.esmile.axis.knowledge.search.KnowledgeSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cross-item two-stage RAG ask (docs/feature/knowledge-ask-rag):
 * hybrid search top-5 → fetch full items → assemble numbered sources → stream the answer
 * with {@code [n]} citations.
 *
 * <p>Read-only and stateless (v1): no tools attached, no conversation memory, nothing
 * is persisted. Zero hits return a canned message without any LLM call.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeAskService {

    /** Returned when retrieval finds nothing — no LLM call is made in that case. */
    public static final String NO_HIT_MESSAGE =
            "知识库中未找到与这个问题相关的内容。可以先把相关资料录入知识库，再来提问。";

    private final KnowledgeSearchService knowledgeSearchService;
    private final KnowledgeItemRepository itemRepository;
    private final ChatGateway chatGateway;

    /** One numbered citation source: n is the 1-based index used in the prompt and answer. */
    public record AskSource(int n, String itemId, String title) {
    }

    public record AskResult(List<AskSource> sources, Flux<String> answer) {
    }

    public AskResult ask(String question) {
        List<KnowledgeSearchHit> hits = knowledgeSearchService.search(question);
        if (hits.isEmpty()) {
            return new AskResult(List.of(), Flux.just(NO_HIT_MESSAGE));
        }
        Map<String, KnowledgeItem> byId = itemRepository.findAllById(
                        hits.stream().map(KnowledgeSearchHit::itemId).toList())
                .stream()
                .collect(Collectors.toMap(KnowledgeItem::getId, Function.identity()));

        List<AskSource> sources = new ArrayList<>();
        StringBuilder assembled = new StringBuilder();
        int n = 1;
        for (KnowledgeSearchHit hit : hits) {
            KnowledgeItem item = byId.get(hit.itemId());
            if (item == null) {
                continue; // 检索与取数之间被删除，跳过
            }
            sources.add(new AskSource(n, item.getId(), item.getTitle()));
            assembled.append('[').append(n).append("] ").append(item.getTitle()).append('\n')
                    .append(KnowledgeAskPrompts.truncateSource(item.getContent())).append("\n\n");
            n++;
        }
        if (sources.isEmpty()) {
            return new AskResult(List.of(), Flux.just(NO_HIT_MESSAGE));
        }
        Flux<String> answer = chatGateway.stream(
                KnowledgeAskPrompts.askSystemPrompt(assembled.toString().strip()),
                question,
                LlmOptions.DEFAULT);
        return new AskResult(List.copyOf(sources), answer);
    }
}
