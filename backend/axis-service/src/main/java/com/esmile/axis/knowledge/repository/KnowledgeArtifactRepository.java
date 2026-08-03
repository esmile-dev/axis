package com.esmile.axis.knowledge.repository;

import com.esmile.axis.knowledge.ArtifactKind;
import com.esmile.axis.knowledge.entity.KnowledgeArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KnowledgeArtifactRepository extends JpaRepository<KnowledgeArtifact, String> {

    Optional<KnowledgeArtifact> findByItemIdAndKind(String itemId, ArtifactKind kind);
}
