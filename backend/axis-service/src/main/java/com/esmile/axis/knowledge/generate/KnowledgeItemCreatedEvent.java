package com.esmile.axis.knowledge.generate;

/**
 * Published by {@code KnowledgeService} after a knowledge item is persisted
 * (paste / URL fetch / file import). Consumed after transaction commit to
 * trigger the async artifact generation pipeline.
 */
public record KnowledgeItemCreatedEvent(String itemId) {
}
