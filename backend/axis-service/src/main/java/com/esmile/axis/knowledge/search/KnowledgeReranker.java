package com.esmile.axis.knowledge.search;

import java.util.List;

/**
 * 融合候选的精排（双阶段检索的第二段）。实现必须保持候选集合不变，只允许重排；
 * 任何失败都应返回原顺序（rerank 是增强，不是单点）。
 */
@FunctionalInterface
public interface KnowledgeReranker {

    /** 对候选按与 query 的相关度重排；失败时返回原列表。 */
    List<KnowledgeSearchHit> rerank(String query, List<KnowledgeSearchHit> candidates);
}
