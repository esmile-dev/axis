package com.esmile.axis.knowledge.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * pgvector 语义检索：similaritySearch topK=5（分块级），按条目去重后返回。
 * store 经 Supplier 每次调用动态获取（AI 配置重载后 store 重建即时生效）；
 * store 为 null（降级中）或 embedding 调用失败等任何异常 → 降级关键词检索 + WARN。
 */
@Slf4j
@RequiredArgsConstructor
public class VectorKnowledgeSearchService implements KnowledgeSearchService {

    private final Supplier<VectorStore> vectorStoreSupplier;
    private final KeywordKnowledgeSearchService fallback;

    @Override
    public List<KnowledgeSearchHit> search(String query) {
        VectorStore vectorStore = vectorStoreSupplier.get();
        if (vectorStore == null) {
            return fallback.search(query);
        }
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
