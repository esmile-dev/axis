package com.esmile.axis.knowledge;

import com.esmile.axis.knowledge.dto.CreateKnowledgeItemRequest;
import com.esmile.axis.knowledge.dto.KnowledgeItemDetailView;
import com.esmile.axis.knowledge.dto.KnowledgeItemSummaryView;
import com.esmile.axis.knowledge.dto.UpdateKnowledgeItemRequest;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.fetch.ArticleExtractor;
import com.esmile.axis.knowledge.fetch.ArticleExtractor.ExtractedArticle;
import com.esmile.axis.knowledge.fetch.WebPageFetcher;
import com.esmile.axis.knowledge.generate.KnowledgeArtifactRegenerationEvent;
import com.esmile.axis.knowledge.generate.KnowledgeItemCreatedEvent;
import com.esmile.axis.knowledge.importer.ImportedFileParser;
import com.esmile.axis.knowledge.importer.ImportedFileParser.ParsedFile;
import com.esmile.axis.knowledge.importer.KnowledgeFileStorage;
import com.esmile.axis.knowledge.repository.KnowledgeArtifactRepository;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeArtifactRepository artifactRepository;
    private final WebPageFetcher webPageFetcher;
    private final ArticleExtractor articleExtractor;
    private final ImportedFileParser importedFileParser;
    private final KnowledgeFileStorage knowledgeFileStorage;
    private final ApplicationEventPublisher eventPublisher;

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
        KnowledgeItem saved = itemRepository.saveAndFlush(item);
        eventPublisher.publishEvent(new KnowledgeItemCreatedEvent(saved.getId()));
        return KnowledgeItemDetailView.from(saved, List.of());
    }

    @Transactional
    public KnowledgeItemDetailView createFromUrl(String url) {
        String html = webPageFetcher.fetch(url);
        ExtractedArticle article = articleExtractor.extract(html);
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.ARTICLE)
                .title(article.title())
                .content(article.markdown())
                .sourceUrl(url)
                .build();
        KnowledgeItem saved = itemRepository.saveAndFlush(item);
        eventPublisher.publishEvent(new KnowledgeItemCreatedEvent(saved.getId()));
        return KnowledgeItemDetailView.from(saved, List.of());
    }

    @Transactional
    public KnowledgeItemDetailView createFromImport(MultipartFile file, KnowledgeType type, String title) {
        String originalFilename = file.getOriginalFilename();
        byte[] bytes = readBytes(file);
        ParsedFile parsed = importedFileParser.parse(originalFilename, bytes);
        String filePath = knowledgeFileStorage.store(bytes, parsed.extension());
        KnowledgeItem item = KnowledgeItem.builder()
                .type(type != null ? type : KnowledgeType.ARTICLE)
                .title(title != null && !title.isBlank() ? title.trim() : stripExtension(originalFilename))
                .content(parsed.text())
                .filePath(filePath)
                .build();
        KnowledgeItem saved = itemRepository.saveAndFlush(item);
        eventPublisher.publishEvent(new KnowledgeItemCreatedEvent(saved.getId()));
        return KnowledgeItemDetailView.from(saved, List.of());
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "EXTRACT_FAILED: 无法读取上传文件");
        }
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
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

    /**
     * Re-run generation for one artifact. The regeneration event fires after this
     * transaction commits, so the async worker never races the GENERATING write
     * (a later commit would overwrite its DONE/FAILED with stale entity state).
     */
    @Transactional
    public KnowledgeItemDetailView regenerateArtifact(String id, ArtifactKind kind) {
        KnowledgeItem item = findOrThrow(id);
        switch (kind) {
            case SUMMARY -> item.setSummaryStatus(ArtifactStatus.GENERATING);
            case MINDMAP -> item.setMindmapStatus(ArtifactStatus.GENERATING);
        }
        KnowledgeItem saved = itemRepository.saveAndFlush(item);
        eventPublisher.publishEvent(new KnowledgeArtifactRegenerationEvent(id, kind));
        return KnowledgeItemDetailView.from(saved, artifactRepository.findByItemId(id));
    }

    private KnowledgeItem findOrThrow(String id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Knowledge item not found: " + id));
    }
}
