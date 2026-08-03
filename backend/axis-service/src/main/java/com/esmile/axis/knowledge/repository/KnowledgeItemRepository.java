package com.esmile.axis.knowledge.repository;

import com.esmile.axis.knowledge.entity.KnowledgeItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeItemRepository extends JpaRepository<KnowledgeItem, String> {

    List<KnowledgeItem> findAllByOrderByCreatedAtDesc();
}
