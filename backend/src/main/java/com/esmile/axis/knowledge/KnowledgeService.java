package com.esmile.axis.knowledge;

import com.esmile.axis.inbox.InboxItem;
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
import com.esmile.axis.knowledge.search.KnowledgeIndexService;
import com.esmile.axis.knowledge.search.KnowledgeItemUpdatedEvent;
import com.esmile.axis.inbox.InboxItemRepository;
import com.esmile.axis.inbox.InboxService;
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
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    /** Same shape rule as FetchKnowledgeRequest: whole trimmed content must be one http/https URL. */
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)^https?://.+");

    private final KnowledgeItemRepository itemRepository;
    private final KnowledgeArtifactRepository artifactRepository;
    private final WebPageFetcher webPageFetcher;
    private final ArticleExtractor articleExtractor;
    private final ImportedFileParser importedFileParser;
    private final KnowledgeFileStorage knowledgeFileStorage;
    private final ApplicationEventPublisher eventPublisher;
    private final InboxItemRepository inboxItemRepository;
    private final InboxService inboxService;
    private final KnowledgeIndexService knowledgeIndexService;

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

    /**
     * Agent 工具通道（KnowledgeTool.createDocument）：纯 NOTE 落库，
     * 不发 {@link KnowledgeItemCreatedEvent}（不触发 artifact 异步生成），与工具历史直存行为一致。
     */
    @Transactional
    public KnowledgeItemSummaryView createNote(String title, String content) {
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.NOTE)
                .title(title)
                .content(content)
                .build();
        return KnowledgeItemSummaryView.from(itemRepository.saveAndFlush(item));
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

    /**
     * FR-008: transfer an inbox item into the knowledge base, three ways: trimmed content
     * that is a single http/https URL goes through the fetch pipeline (ARTICLE); otherwise
     * a valid http(s) URL in the item's link field (DIGEST items keep the URL there, with
     * content holding only the title) is fetched the same way, title auto-extracted;
     * anything else is stored as a NOTE titled by its first line (max 50 chars). On
     * success the inbox item is marked read via InboxService's existing logic; the inbox
     * item is never deleted.
     */
    @Transactional
    public KnowledgeItemDetailView createFromInbox(String inboxItemId) {
        InboxItem inboxItem = inboxItemRepository.findById(inboxItemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inbox item not found: " + inboxItemId));
        String content = inboxItem.getContent().trim();
        String link = inboxItem.getLink() != null ? inboxItem.getLink().trim() : "";
        KnowledgeItemDetailView view;
        if (URL_PATTERN.matcher(content).matches()) {
            view = createFromUrl(content);
        } else if (URL_PATTERN.matcher(link).matches()) {
            view = createFromUrl(link);
        } else {
            view = create(new CreateKnowledgeItemRequest(KnowledgeType.NOTE, deriveTitle(content), content, null, null));
        }
        inboxService.update(inboxItemId, null, null, true);
        return view;
    }

    private static String deriveTitle(String content) {
        String firstLine = content.lines().findFirst().orElse(content).trim();
        return firstLine.length() > 50 ? firstLine.substring(0, 50) : firstLine;
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
        // 仅标题实际变化才触发向量重建（标题前置进每条 chunk 参与 embedding）；
        // status/progress/tags 不进向量，不为它们白调 embedding API
        if (req.title() != null && !req.title().equals(item.getTitle())) {
            item.setTitle(req.title());
            eventPublisher.publishEvent(new KnowledgeItemUpdatedEvent(id));
        }
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
        // FR-010: 同步删除向量分块；实现内部捕获异常，不阻塞删除主流程
        knowledgeIndexService.removeItem(id);
    }

    /** 全量重建向量索引（分块算法或 embedding 配置变更后使用）；indexItem 幂等且内部捕获异常，逐条先删后写。 */
    public int reindexAll() {
        List<String> ids = itemRepository.findAllIds();
        ids.forEach(knowledgeIndexService::indexItem);
        return ids.size();
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
