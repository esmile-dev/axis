package com.esmile.axis.knowledge.search;

/** 知识检索命中：条目 id + 标题 + 命中片段（截断 ~200 字符）。 */
public record KnowledgeSearchHit(String itemId, String title, String snippet) {
}
