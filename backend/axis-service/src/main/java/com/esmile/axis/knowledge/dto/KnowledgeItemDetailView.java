package com.esmile.axis.knowledge.dto;

import com.esmile.axis.knowledge.ArtifactStatus;
import com.esmile.axis.knowledge.KnowledgeStatus;
import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.entity.KnowledgeArtifact;
import com.esmile.axis.knowledge.entity.KnowledgeItem;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record KnowledgeItemDetailView(
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
        Instant updatedAt,
        String content,
        List<ArtifactView> artifacts
) {

    public static KnowledgeItemDetailView from(KnowledgeItem item, List<KnowledgeArtifact> artifacts) {
        return new KnowledgeItemDetailView(
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
                item.getUpdatedAt(),
                item.getContent(),
                artifacts.stream().map(ArtifactView::from).toList()
        );
    }
}
