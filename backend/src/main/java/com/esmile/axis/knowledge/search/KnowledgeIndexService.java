package com.esmile.axis.knowledge.search;

/**
 * 知识条目向量索引生命周期。实现永不抛异常：向量不可用（noop）或
 * embedding/写入失败（WARN 日志）都不影响摄取与产物状态机。
 */
public interface KnowledgeIndexService {

    String META_ITEM_ID = "item_id";
    String META_TITLE = "title";

    /** 全量重建该条目的向量（先删旧分块再写入新分块）。 */
    void indexItem(String itemId);

    /** 删除该条目的全部向量分块。 */
    void removeItem(String itemId);
}
