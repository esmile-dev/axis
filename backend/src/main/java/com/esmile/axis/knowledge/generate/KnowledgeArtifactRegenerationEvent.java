package com.esmile.axis.knowledge.generate;

import com.esmile.axis.knowledge.ArtifactKind;

/**
 * Published by {@code KnowledgeService.regenerateArtifact} after the target
 * artifact status is persisted as GENERATING. Consumed after transaction commit
 * to re-run generation for a single artifact.
 */
public record KnowledgeArtifactRegenerationEvent(String itemId, ArtifactKind kind) {
}
