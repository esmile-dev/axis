package com.esmile.axis.repository;

import com.esmile.axis.entity.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, String> {

    List<KnowledgeDocument> findAllByOrderByCreatedAtDesc();
}
