package com.esmile.axis.knowledge.dto;

import com.esmile.axis.knowledge.ArtifactKind;
import com.esmile.axis.knowledge.entity.KnowledgeArtifact;

import java.time.Instant;

public record ArtifactView(
        ArtifactKind kind,
        String content,
        String model,
        String error,
        Instant updatedAt
) {

    public static ArtifactView from(KnowledgeArtifact artifact) {
        return new ArtifactView(
                artifact.getKind(),
                artifact.getContent(),
                artifact.getModel(),
                artifact.getError(),
                artifact.getUpdatedAt()
        );
    }
}
