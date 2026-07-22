package com.esmile.axis.service;

import com.esmile.axis.entity.KnowledgeDocument;
import com.esmile.axis.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeDocumentRepository repository;

    @Transactional(readOnly = true)
    public List<KnowledgeDocument> findAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public KnowledgeDocument findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("KnowledgeDocument not found: " + id));
    }

    @Transactional
    public KnowledgeDocument create(String title, String content) {
        KnowledgeDocument doc = KnowledgeDocument.builder()
                .title(title)
                .content(content)
                .build();
        return repository.save(doc);
    }

    @Transactional
    public KnowledgeDocument update(String id, String title, String content) {
        KnowledgeDocument doc = findById(id);
        if (title != null) doc.setTitle(title);
        if (content != null) doc.setContent(content);
        return repository.save(doc);
    }

    @Transactional
    public void delete(String id) {
        repository.deleteById(id);
    }
}
