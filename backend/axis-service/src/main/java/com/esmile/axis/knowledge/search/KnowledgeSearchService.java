package com.esmile.axis.knowledge.search;

import java.util.List;

/** FR-010：知识库检索。向量（pgvector）与关键词（DB LIKE）两实现，装配见 {@link KnowledgeSearchConfig}。 */
public interface KnowledgeSearchService {

    /** 返回 top-5 命中（按相关度/时间排序，按条目去重）。 */
    List<KnowledgeSearchHit> search(String query);
}
