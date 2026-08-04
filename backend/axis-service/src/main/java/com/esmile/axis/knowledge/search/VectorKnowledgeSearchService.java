package com.esmile.axis.knowledge.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * pgvector 语义检索：similaritySearch topK=5（分块级），按条目去重后返回。
 * embedding 调用失败等任何异常 → 降级关键词检索 + WARN（运行时降级，不抛给调用方）。
 */
@Slf4j
@RequiredArgsConstructor
public class VectorKnowledgeSearchService implements KnowledgeSearchService {

    private final VectorStore vectorStore;
    private final KeywordKnowledgeSearchService fallback;

    @Override
    public List<KnowledgeSearchHit> search(String query) {
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(KeywordKnowledgeSearchService.TOP_K).build());
            Map<String, KnowledgeSearchHit> byItem = new LinkedHashMap<>();
            for (Document doc : docs) {
                Object itemId = doc.getMetadata().get(KnowledgeIndexService.META_ITEM_ID);
                if (itemId == null) {
                    continue;
                }
                byItem.putIfAbsent(itemId.toString(), new KnowledgeSearchHit(
                        itemId.toString(),
                        String.valueOf(doc.getMetadata().getOrDefault(KnowledgeIndexService.META_TITLE, "")),
                        KeywordKnowledgeSearchService.snippet(doc.getText())));
            }
            return List.copyOf(byItem.values());
        } catch (Exception e) {
            log.warn("knowledge.search.fallback reason={} — 向量检索失败，降级关键词检索", e.toString());
            return fallback.search(query);
        }
    }
}
