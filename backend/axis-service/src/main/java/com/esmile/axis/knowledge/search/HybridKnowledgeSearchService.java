package com.esmile.axis.knowledge.search;

import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：向量泳道（语义）+ 关键词泳道（精确 token）各取 top-N，
 * RRF（Reciprocal Rank Fusion，k=60）按名次融合，可选 reranker 精排，输出 top-5。
 *
 * <p>降级链：向量泳道异常 → 关键词 top-5；store 为 null 时向量泳道返回空，
 * 融合结果自然等于关键词序——两种降级都不抛异常。rerank 失败由 {@link KnowledgeReranker} 内部兜底。
 */
@Slf4j
public class HybridKnowledgeSearchService implements KnowledgeSearchService {

    static final int RRF_K = 60;
    static final int FINAL_TOP_K = 5;
    static final int RERANK_CANDIDATES = 10;

    private final VectorKnowledgeSearchService vectorLane;
    private final KeywordKnowledgeSearchService keywordLane;
    private final KnowledgeReranker reranker;
    private final int keywordTopK;

    public HybridKnowledgeSearchService(VectorKnowledgeSearchService vectorLane,
                                        KeywordKnowledgeSearchService keywordLane,
                                        KnowledgeReranker reranker,
                                        int keywordTopK) {
        this.vectorLane = vectorLane;
        this.keywordLane = keywordLane;
        this.reranker = reranker;
        this.keywordTopK = keywordTopK;
    }

    @Override
    public List<KnowledgeSearchHit> search(String query) {
        List<KnowledgeSearchHit> keywordHits = keywordLane.search(query, keywordTopK);
        List<KnowledgeSearchHit> vectorHits;
        try {
            vectorHits = vectorLane.vectorHits(query);
        } catch (Exception e) {
            log.warn("knowledge.search.fallback reason={} — 向量泳道失败，降级关键词检索", e.toString());
            return keywordHits.stream().limit(FINAL_TOP_K).toList();
        }
        List<KnowledgeSearchHit> fused = rrfMerge(vectorHits, keywordHits, RERANK_CANDIDATES);
        return reranker.rerank(query, fused).stream().limit(FINAL_TOP_K).toList();
    }

    /**
     * RRF 融合：score = Σ 1/(k + rank)。只融合名次不融合原始分数——两泳道分数尺度不可比
     * （cosine similarity vs LIKE 无分数），名次融合免归一化且对离群分数鲁棒。
     * 向量泳道在前：同一条目两路都中时，snippet 取向量命中的语义分块。
     */
    static List<KnowledgeSearchHit> rrfMerge(List<KnowledgeSearchHit> vectorHits,
                                             List<KnowledgeSearchHit> keywordHits, int limit) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, KnowledgeSearchHit> hits = new LinkedHashMap<>();
        for (int i = 0; i < vectorHits.size(); i++) {
            KnowledgeSearchHit h = vectorHits.get(i);
            scores.merge(h.itemId(), 1.0 / (RRF_K + i + 1), Double::sum);
            hits.putIfAbsent(h.itemId(), h);
        }
        for (int i = 0; i < keywordHits.size(); i++) {
            KnowledgeSearchHit h = keywordHits.get(i);
            scores.merge(h.itemId(), 1.0 / (RRF_K + i + 1), Double::sum);
            hits.putIfAbsent(h.itemId(), h);
        }
        return hits.values().stream()
                .sorted(Comparator.comparingDouble((KnowledgeSearchHit h) -> scores.get(h.itemId())).reversed())
                .limit(limit)
                .toList();
    }
}
