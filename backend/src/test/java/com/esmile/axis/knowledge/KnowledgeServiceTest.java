package com.esmile.axis.knowledge;

import com.esmile.axis.inbox.InboxItem;
import com.esmile.axis.knowledge.dto.CreateKnowledgeItemRequest;
import com.esmile.axis.knowledge.dto.KnowledgeItemDetailView;
import com.esmile.axis.knowledge.dto.KnowledgeItemSummaryView;
import com.esmile.axis.knowledge.dto.UpdateKnowledgeItemRequest;
import com.esmile.axis.knowledge.entity.KnowledgeArtifact;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KnowledgeService}: create defaults, list filter pass-through,
 * get 404, partial update null-skipping, and delete cascading artifacts.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock
    private KnowledgeItemRepository itemRepository;
    @Mock
    private KnowledgeArtifactRepository artifactRepository;
    @Mock
    private WebPageFetcher webPageFetcher;
    @Mock
    private ArticleExtractor articleExtractor;
    @Mock
    private ImportedFileParser importedFileParser;
    @Mock
    private KnowledgeFileStorage knowledgeFileStorage;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private InboxItemRepository inboxItemRepository;
    @Mock
    private InboxService inboxService;
    @Mock
    private KnowledgeIndexService knowledgeIndexService;

    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(itemRepository, artifactRepository, webPageFetcher, articleExtractor,
                importedFileParser, knowledgeFileStorage, eventPublisher, inboxItemRepository, inboxService,
                knowledgeIndexService);
    }

    @Test
    void create_minimalRequest_appliesDefaults() {
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItemDetailView view = service.create(
                new CreateKnowledgeItemRequest(null, "标题", "正文", null, null));

        ArgumentCaptor<KnowledgeItem> captor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).saveAndFlush(captor.capture());
        KnowledgeItem saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(KnowledgeType.ARTICLE);
        assertThat(saved.getStatus()).isEqualTo(KnowledgeStatus.UNREAD);
        assertThat(saved.getProgress()).isZero();
        assertThat(saved.getSummaryStatus()).isEqualTo(ArtifactStatus.PENDING);
        assertThat(saved.getMindmapStatus()).isEqualTo(ArtifactStatus.PENDING);
        assertThat(saved.getTags()).isEmpty();
        assertThat(saved.getSourceUrl()).isNull();
        assertThat(view.content()).isEqualTo("正文");
        assertThat(view.artifacts()).isEmpty();
        verify(eventPublisher).publishEvent(any(KnowledgeItemCreatedEvent.class));
    }

    @Test
    void create_fullRequest_keepsGivenValues() {
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItemDetailView view = service.create(new CreateKnowledgeItemRequest(
                KnowledgeType.BOOK, "书", "内容", "https://example.com", Set.of("a", "b")));

        assertThat(view.type()).isEqualTo(KnowledgeType.BOOK);
        assertThat(view.sourceUrl()).isEqualTo("https://example.com");
        assertThat(view.tags()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void createFromUrl_success_savesFetchedArticleAsPending() {
        when(webPageFetcher.fetch("https://example.com/a")).thenReturn("<html>page</html>");
        when(articleExtractor.extract("<html>page</html>"))
                .thenReturn(new ExtractedArticle("抓取标题", "# 正文"));
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItemDetailView view = service.createFromUrl("https://example.com/a");

        ArgumentCaptor<KnowledgeItem> captor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).saveAndFlush(captor.capture());
        KnowledgeItem saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(KnowledgeType.ARTICLE);
        assertThat(saved.getTitle()).isEqualTo("抓取标题");
        assertThat(saved.getContent()).isEqualTo("# 正文");
        assertThat(saved.getSourceUrl()).isEqualTo("https://example.com/a");
        assertThat(saved.getSummaryStatus()).isEqualTo(ArtifactStatus.PENDING);
        assertThat(saved.getMindmapStatus()).isEqualTo(ArtifactStatus.PENDING);
        assertThat(view.content()).isEqualTo("# 正文");
        verify(eventPublisher).publishEvent(any(KnowledgeItemCreatedEvent.class));
    }

    @Test
    void createFromUrl_fetchFails_propagates422FetchFailed() {
        when(webPageFetcher.fetch(anyString())).thenThrow(
                new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "FETCH_FAILED: boom"));

        assertThatThrownBy(() -> service.createFromUrl("https://down.example.com"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).contains("FETCH_FAILED");
                });
        verifyNoInteractions(articleExtractor);
        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    void createFromUrl_extractFails_propagates422ExtractFailed() {
        when(webPageFetcher.fetch(anyString())).thenReturn("<html></html>");
        when(articleExtractor.extract(anyString())).thenThrow(
                new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "EXTRACT_FAILED: empty"));

        assertThatThrownBy(() -> service.createFromUrl("https://example.com"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).contains("EXTRACT_FAILED");
                });
        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    void createFromImport_defaults_appliesArticleTypeAndFilenameTitle() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "读书笔记.md", "text/markdown", "# 正文".getBytes(StandardCharsets.UTF_8));
        when(importedFileParser.parse(eq("读书笔记.md"), any())).thenReturn(new ParsedFile(".md", "# 正文"));
        when(knowledgeFileStorage.store(any(), eq(".md"))).thenReturn("knowledge/uuid-1.md");
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItemDetailView view = service.createFromImport(file, null, null);

        ArgumentCaptor<KnowledgeItem> captor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).saveAndFlush(captor.capture());
        KnowledgeItem saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(KnowledgeType.ARTICLE);
        assertThat(saved.getTitle()).isEqualTo("读书笔记");
        assertThat(saved.getContent()).isEqualTo("# 正文");
        assertThat(saved.getFilePath()).isEqualTo("knowledge/uuid-1.md");
        assertThat(saved.getSummaryStatus()).isEqualTo(ArtifactStatus.PENDING);
        assertThat(saved.getMindmapStatus()).isEqualTo(ArtifactStatus.PENDING);
        assertThat(view.content()).isEqualTo("# 正文");
        verify(eventPublisher).publishEvent(any(KnowledgeItemCreatedEvent.class));
    }

    @Test
    void createFromImport_explicitTypeAndTitle_keepsThem() {
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[]{1, 2});
        when(importedFileParser.parse(eq("x.pdf"), any())).thenReturn(new ParsedFile(".pdf", "PDF 文本"));
        when(knowledgeFileStorage.store(any(), eq(".pdf"))).thenReturn("knowledge/uuid-2.pdf");
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItemDetailView view = service.createFromImport(file, KnowledgeType.BOOK, " 自定义标题 ");

        assertThat(view.type()).isEqualTo(KnowledgeType.BOOK);
        assertThat(view.title()).isEqualTo("自定义标题");
    }

    @Test
    void createFromImport_blankTitle_fallsBackToFilename() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "报告.txt", "text/plain", "正文".getBytes(StandardCharsets.UTF_8));
        when(importedFileParser.parse(eq("报告.txt"), any())).thenReturn(new ParsedFile(".txt", "正文"));
        when(knowledgeFileStorage.store(any(), eq(".txt"))).thenReturn("knowledge/uuid-3.txt");
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItemDetailView view = service.createFromImport(file, null, "  ");

        assertThat(view.title()).isEqualTo("报告");
    }

    @Test
    void createFromImport_parseFails_storesAndSavesNothing() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.exe", "application/octet-stream", new byte[]{1});
        when(importedFileParser.parse(any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE: 仅支持 .md/.txt/.pdf"));

        assertThatThrownBy(() -> service.createFromImport(file, null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        verifyNoInteractions(knowledgeFileStorage);
        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    void createFromInbox_urlContent_fetchesArticleAndMarksRead() {
        when(inboxItemRepository.findById("in1")).thenReturn(Optional.of(inboxItem("in1", "https://example.com/a")));
        when(webPageFetcher.fetch("https://example.com/a")).thenReturn("<html>page</html>");
        when(articleExtractor.extract("<html>page</html>"))
                .thenReturn(new ExtractedArticle("抓取标题", "# 正文"));
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItemDetailView view = service.createFromInbox("in1");

        ArgumentCaptor<KnowledgeItem> captor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).saveAndFlush(captor.capture());
        KnowledgeItem saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(KnowledgeType.ARTICLE);
        assertThat(saved.getSourceUrl()).isEqualTo("https://example.com/a");
        assertThat(view.title()).isEqualTo("抓取标题");
        verify(inboxService).update("in1", null, null, true);
        verify(eventPublisher).publishEvent(any(KnowledgeItemCreatedEvent.class));
    }

    @Test
    void createFromInbox_paddedUrlContent_trimsThenFetches() {
        when(inboxItemRepository.findById("in1"))
                .thenReturn(Optional.of(inboxItem("in1", "  https://example.com/a\n")));
        when(webPageFetcher.fetch("https://example.com/a")).thenReturn("<html>page</html>");
        when(articleExtractor.extract("<html>page</html>"))
                .thenReturn(new ExtractedArticle("抓取标题", "# 正文"));
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createFromInbox("in1");

        verify(webPageFetcher).fetch("https://example.com/a");
        verify(inboxService).update("in1", null, null, true);
    }

    @Test
    void createFromInbox_digestLink_fetchesLinkAndMarksRead() {
        // DIGEST 条目：content 只存标题，URL 在 link 字段（T-010 新增分支）
        InboxItem digest = inboxItem("in4", "36氪每日精选：某文章标题");
        digest.setLink("https://example.com/digest-article");
        when(inboxItemRepository.findById("in4")).thenReturn(Optional.of(digest));
        when(webPageFetcher.fetch("https://example.com/digest-article")).thenReturn("<html>page</html>");
        when(articleExtractor.extract("<html>page</html>"))
                .thenReturn(new ExtractedArticle("抓取标题", "# 正文"));
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItemDetailView view = service.createFromInbox("in4");

        ArgumentCaptor<KnowledgeItem> captor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).saveAndFlush(captor.capture());
        KnowledgeItem saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(KnowledgeType.ARTICLE);
        assertThat(saved.getSourceUrl()).isEqualTo("https://example.com/digest-article");
        assertThat(view.title()).isEqualTo("抓取标题");
        verify(inboxService).update("in4", null, null, true);
        verify(eventPublisher).publishEvent(any(KnowledgeItemCreatedEvent.class));
    }

    @Test
    void createFromInbox_plainText_createsNoteTitledByFirstLineAndMarksRead() {
        when(inboxItemRepository.findById("in2"))
                .thenReturn(Optional.of(inboxItem("in2", "  记录一下这个想法\n第二行补充  ")));
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeItemDetailView view = service.createFromInbox("in2");

        ArgumentCaptor<KnowledgeItem> captor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).saveAndFlush(captor.capture());
        KnowledgeItem saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(KnowledgeType.NOTE);
        assertThat(saved.getTitle()).isEqualTo("记录一下这个想法");
        assertThat(saved.getContent()).isEqualTo("记录一下这个想法\n第二行补充");
        assertThat(view.type()).isEqualTo(KnowledgeType.NOTE);
        verifyNoInteractions(webPageFetcher);
        verify(inboxService).update("in2", null, null, true);
        verify(eventPublisher).publishEvent(any(KnowledgeItemCreatedEvent.class));
    }

    @Test
    void createFromInbox_longFirstLine_truncatesTitleTo50Chars() {
        String longLine = "很".repeat(60);
        when(inboxItemRepository.findById("in3"))
                .thenReturn(Optional.of(inboxItem("in3", longLine + "\n第二行")));
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createFromInbox("in3");

        ArgumentCaptor<KnowledgeItem> captor = ArgumentCaptor.forClass(KnowledgeItem.class);
        verify(itemRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("很".repeat(50));
        verify(inboxService).update("in3", null, null, true);
    }

    @Test
    void createFromInbox_missingInboxItem_throwsNotFound() {
        when(inboxItemRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createFromInbox("nope"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(inboxService, webPageFetcher);
        verify(itemRepository, never()).saveAndFlush(any());
    }

    @Test
    void list_noFilters_passesNullsAndMapsSummaries() {
        KnowledgeItem item = item("i1", "标题一");
        when(itemRepository.search(null, null, null, null)).thenReturn(List.of(item));

        List<KnowledgeItemSummaryView> views = service.list(null, null, null, null);

        assertThat(views).hasSize(1);
        KnowledgeItemSummaryView view = views.get(0);
        assertThat(view.id()).isEqualTo("i1");
        assertThat(view.type()).isEqualTo(KnowledgeType.NOTE);
        assertThat(view.status()).isEqualTo(KnowledgeStatus.UNREAD);
        assertThat(view.summaryStatus()).isEqualTo(ArtifactStatus.PENDING);
        assertThat(view.tags()).containsExactly("t1");
    }

    @Test
    void list_allFilters_passesThemToRepository() {
        when(itemRepository.search(KnowledgeType.ARTICLE, KnowledgeStatus.READING, "java", "并发"))
                .thenReturn(List.of());

        List<KnowledgeItemSummaryView> views = service.list(KnowledgeType.ARTICLE, KnowledgeStatus.READING, "java", "并发");

        assertThat(views).isEmpty();
        verify(itemRepository).search(KnowledgeType.ARTICLE, KnowledgeStatus.READING, "java", "并发");
    }

    @Test
    void get_existing_returnsDetailWithArtifacts() {
        KnowledgeItem item = item("i1", "标题");
        KnowledgeArtifact artifact = KnowledgeArtifact.builder()
                .item(item).kind(ArtifactKind.SUMMARY).content("摘要").model("gpt-4o").build();
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(artifactRepository.findByItemId("i1")).thenReturn(List.of(artifact));

        KnowledgeItemDetailView view = service.get("i1");

        assertThat(view.content()).isEqualTo("正文 i1");
        assertThat(view.artifacts()).hasSize(1);
        assertThat(view.artifacts().get(0).kind()).isEqualTo(ArtifactKind.SUMMARY);
        assertThat(view.artifacts().get(0).content()).isEqualTo("摘要");
        assertThat(view.artifacts().get(0).model()).isEqualTo("gpt-4o");
    }

    @Test
    void get_missing_throwsNotFound() {
        when(itemRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("nope"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void update_allNullFields_skipsEverything() {
        KnowledgeItem item = item("i1", "原标题");
        item.setTags(new HashSet<>(Set.of("keep")));
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(artifactRepository.findByItemId("i1")).thenReturn(List.of());

        KnowledgeItemDetailView view = service.update("i1", new UpdateKnowledgeItemRequest(null, null, null, null));

        assertThat(view.title()).isEqualTo("原标题");
        assertThat(view.status()).isEqualTo(KnowledgeStatus.UNREAD);
        assertThat(view.progress()).isZero();
        assertThat(view.tags()).containsExactly("keep");
    }

    @Test
    void update_givenFields_updatesThem() {
        KnowledgeItem item = item("i1", "原标题");
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(artifactRepository.findByItemId("i1")).thenReturn(List.of());

        KnowledgeItemDetailView view = service.update("i1",
                new UpdateKnowledgeItemRequest("新标题", KnowledgeStatus.DONE, 100, Set.of("x")));

        assertThat(view.title()).isEqualTo("新标题");
        assertThat(view.status()).isEqualTo(KnowledgeStatus.DONE);
        assertThat(view.progress()).isEqualTo(100);
        assertThat(view.tags()).containsExactly("x");
    }

    @Test
    void update_titleChanged_publishesReindexEvent() {
        KnowledgeItem item = item("i1", "原标题");
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(artifactRepository.findByItemId("i1")).thenReturn(List.of());

        service.update("i1", new UpdateKnowledgeItemRequest("新标题", null, null, null));

        verify(eventPublisher).publishEvent(new KnowledgeItemUpdatedEvent("i1"));
    }

    @Test
    void update_titleUnchanged_skipsReindexEvent() {
        KnowledgeItem item = item("i1", "原标题");
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(artifactRepository.findByItemId("i1")).thenReturn(List.of());

        service.update("i1", new UpdateKnowledgeItemRequest("原标题", KnowledgeStatus.DONE, 100, Set.of("x")));

        verify(eventPublisher, never()).publishEvent(any(KnowledgeItemUpdatedEvent.class));
    }

    @Test
    void update_missing_throwsNotFound() {
        when(itemRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("nope", new UpdateKnowledgeItemRequest(null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void delete_existing_cascadesArtifacts() {
        KnowledgeItem item = item("i1", "标题");
        KnowledgeArtifact artifact = KnowledgeArtifact.builder()
                .item(item).kind(ArtifactKind.MINDMAP).content("graph").build();
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(artifactRepository.findByItemId("i1")).thenReturn(List.of(artifact));

        service.delete("i1");

        verify(artifactRepository).deleteAll(List.of(artifact));
        verify(itemRepository).delete(item);
    }

    @Test
    void delete_missing_throwsNotFound() {
        when(itemRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("nope"))
                .isInstanceOf(ResponseStatusException.class);
        verify(artifactRepository, never()).deleteAll(anyList());
        verify(itemRepository, never()).delete(any());
    }

    @Test
    void reindexAll_rebuildsEveryItem() {
        when(itemRepository.findAllIds()).thenReturn(List.of("i1", "i2"));

        int count = service.reindexAll();

        assertThat(count).isEqualTo(2);
        verify(knowledgeIndexService).indexItem("i1");
        verify(knowledgeIndexService).indexItem("i2");
    }

    @Test
    void regenerateArtifact_existing_marksGeneratingAndPublishesEvent() {
        KnowledgeItem item = item("i1", "标题");
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        when(itemRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(artifactRepository.findByItemId("i1")).thenReturn(List.of());

        KnowledgeItemDetailView view = service.regenerateArtifact("i1", ArtifactKind.SUMMARY);

        assertThat(item.getSummaryStatus()).isEqualTo(ArtifactStatus.GENERATING);
        assertThat(item.getMindmapStatus()).isEqualTo(ArtifactStatus.PENDING);
        assertThat(view.summaryStatus()).isEqualTo(ArtifactStatus.GENERATING);
        verify(itemRepository).saveAndFlush(item);
        verify(eventPublisher).publishEvent(new KnowledgeArtifactRegenerationEvent("i1", ArtifactKind.SUMMARY));
    }

    @Test
    void regenerateArtifact_missing_throwsNotFound() {
        when(itemRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.regenerateArtifact("nope", ArtifactKind.MINDMAP))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(eventPublisher, never()).publishEvent(any());
    }

    private static InboxItem inboxItem(String id, String content) {
        InboxItem item = InboxItem.builder().content(content).build();
        item.setId(id);
        return item;
    }

    private static KnowledgeItem item(String id, String title) {
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.NOTE)
                .title(title)
                .content("正文 " + id)
                .tags(new HashSet<>(Set.of("t1")))
                .build();
        item.setId(id);
        return item;
    }
}
