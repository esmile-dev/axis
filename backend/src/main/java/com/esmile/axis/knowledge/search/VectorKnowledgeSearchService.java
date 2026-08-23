package com.esmile.axis.knowledge.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * pgvector 语义检索泳道：similaritySearch（分块级，topK 与相似度阈值可配），按条目去重。
 * store 经 Supplier 每次调用动态获取（AI 配置重载后 store 重建即时生效）。
 *
 * <p>{@link #vectorHits} 是纯泳道方法（store 为 null 返回空，异常上抛），供混合检索组合；
 * {@link #search} 保留独立用法：store 为 null 或任何异常 → 降级关键词检索 + WARN。
 */
@Slf4j
public class VectorKnowledgeSearchService implements KnowledgeSearchService {

    private final Supplier<VectorStore> vectorStoreSupplier;
    private final KeywordKnowledgeSearchService fallback;
    private final int topK;
    private final double similarityThreshold;

    public VectorKnowledgeSearchService(Supplier<VectorStore> vectorStoreSupplier,
                                        KeywordKnowledgeSearchService fallback,
                                        int topK, double similarityThreshold) {
        this.vectorStoreSupplier = vectorStoreSupplier;
        this.fallback = fallback;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    @Override
    public List<KnowledgeSearchHit> search(String query) {
        VectorStore vectorStore = vectorStoreSupplier.get();
        if (vectorStore == null) {
            return fallback.search(query);
        }
        try {
            return vectorHits(query).stream().limit(KeywordKnowledgeSearchService.TOP_K).toList();
        } catch (Exception e) {
            log.warn("knowledge.search.fallback reason={} — 向量检索失败，降级关键词检索", e.toString());
            return fallback.search(query);
        }
    }

    /** 纯向量泳道（不降级）：store 为 null 返回空列表；任何异常上抛，由调用方决定降级策略。 */
    public List<KnowledgeSearchHit> vectorHits(String query) {
        VectorStore vectorStore = vectorStoreSupplier.get();
        if (vectorStore == null) {
            return List.of();
        }
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(topK).similarityThreshold(similarityThreshold).build());
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
    }
}
