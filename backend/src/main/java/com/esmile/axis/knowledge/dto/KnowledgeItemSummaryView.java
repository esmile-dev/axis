package com.esmile.axis.knowledge.dto;

import com.esmile.axis.knowledge.ArtifactStatus;
import com.esmile.axis.knowledge.KnowledgeStatus;
import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.entity.KnowledgeItem;

import java.time.Instant;
import java.util.Set;

public record KnowledgeItemSummaryView(
        String id,
        KnowledgeType type,
        String title,
        KnowledgeStatus status,
        int progress,
        ArtifactStatus summaryStatus,
        ArtifactStatus mindmapStatus,
        Set<String> tags,
        String sourceUrl,
        Instant createdAt,
        Instant updatedAt
) {

    public static KnowledgeItemSummaryView from(KnowledgeItem item) {
        return new KnowledgeItemSummaryView(
                item.getId(),
                item.getType(),
                item.getTitle(),
                item.getStatus(),
                item.getProgress(),
                item.getSummaryStatus(),
                item.getMindmapStatus(),
                Set.copyOf(item.getTags()),
                item.getSourceUrl(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }
}
