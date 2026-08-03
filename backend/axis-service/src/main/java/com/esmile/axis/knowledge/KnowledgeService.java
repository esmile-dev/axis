package com.esmile.axis.knowledge;

import com.esmile.axis.knowledge.dto.CreateKnowledgeItemRequest;
import com.esmile.axis.knowledge.dto.KnowledgeItemDetailView;
import com.esmile.axis.knowledge.dto.KnowledgeItemSummaryView;
import com.esmile.axis.knowledge.dto.UpdateKnowledgeItemRequest;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeArtifactRepository;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeArtifactRepository artifactRepository;

    @Transactional(readOnly = true)
    public List<KnowledgeItemSummaryView> list(KnowledgeType type, KnowledgeStatus status, String tag, String q) {
        return itemRepository.search(type, status, tag, q).stream()
                .map(KnowledgeItemSummaryView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeItemDetailView get(String id) {
        KnowledgeItem item = findOrThrow(id);
        return KnowledgeItemDetailView.from(item, artifactRepository.findByItemId(id));
    }

    @Transactional
    public KnowledgeItemDetailView create(CreateKnowledgeItemRequest req) {
        KnowledgeItem item = KnowledgeItem.builder()
                .type(req.type() != null ? req.type() : KnowledgeType.ARTICLE)
                .title(req.title())
                .content(req.content())
                .sourceUrl(req.sourceUrl())
                .tags(req.tags() != null ? new HashSet<>(req.tags()) : new HashSet<>())
                .build();
        return KnowledgeItemDetailView.from(itemRepository.saveAndFlush(item), List.of());
    }

    @Transactional
    public KnowledgeItemDetailView update(String id, UpdateKnowledgeItemRequest req) {
        KnowledgeItem item = findOrThrow(id);
        if (req.title() != null) item.setTitle(req.title());
        if (req.status() != null) item.setStatus(req.status());
        if (req.progress() != null) item.setProgress(req.progress());
        if (req.tags() != null) item.setTags(new HashSet<>(req.tags()));
        return KnowledgeItemDetailView.from(itemRepository.saveAndFlush(item), artifactRepository.findByItemId(id));
    }

    @Transactional
    public void delete(String id) {
        KnowledgeItem item = findOrThrow(id);
        artifactRepository.deleteAll(artifactRepository.findByItemId(id));
        itemRepository.delete(item);
    }

    private KnowledgeItem findOrThrow(String id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge item not found: " + id));
    }
}
