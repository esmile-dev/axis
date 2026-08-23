package com.esmile.axis.knowledge.search;

/**
 * 条目标题实际变化时由 {@code KnowledgeService} 发布——标题前置进每条 chunk 参与
 * embedding（status/progress/tags 不进向量，变化不发布）；提交后触发幂等重建（先删后写）。
 */
public record KnowledgeItemUpdatedEvent(String itemId) {
}
