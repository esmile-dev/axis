package com.esmile.axis.digest.service;

import com.esmile.axis.digest.classify.Classifier;
import com.esmile.axis.inbox.DigestCategory;
import com.esmile.axis.digest.fetch.Article;
import com.esmile.axis.digest.fetch.RssFetcherService;
import com.esmile.axis.digest.summarize.ArticleSummary;
import com.esmile.axis.digest.summarize.ArticleSummaryCache;
import com.esmile.axis.digest.summarize.ArticleSummaryCacheRepository;
import com.esmile.axis.digest.summarize.SummarizationService;
import com.esmile.axis.digest.store.DigestExecutionLog;
import com.esmile.axis.digest.store.DigestExecutionLogRepository;
import com.esmile.axis.inbox.InboxItem;
import com.esmile.axis.inbox.InboxItemType;
import com.esmile.axis.inbox.InboxItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DailyDigestService} covering the Digest 2.0 pipeline:
 * cache hit bypass, LLM summarize/editor integration, fallback, and llmCallCount.
 */
@ExtendWith(MockitoExtension.class)
class DailyDigestServiceTest {

    @Mock
    private DigestExecutionLogRepository logRepository;
    @Mock
    private InboxItemRepository inboxRepository;
    @Mock
    private RssFetcherService fetcherService;
    @Mock
    private Classifier classifier;
    @Mock
    private SummarizationService summarizationService;
    @Mock
    private ArticleSummaryCacheRepository cacheRepository;

    private DailyDigestService service;

    @BeforeEach
    void setUp() {
        service = new DailyDigestService(logRepository, inboxRepository, fetcherService,
                classifier, summarizationService, cacheRepository);
    }

    @Test
    void trigger_firstRun_summarizesAndEdits() {
        stubEmptyLog();
        Article article = article("u1", DigestCategory.AI_FRONTIER);
        when(fetcherService.fetchAll()).thenReturn(List.of(article));
        when(classifier.classify(any())).thenReturn(DigestCategory.AI_FRONTIER);
        when(cacheRepository.findByLink("u1")).thenReturn(Optional.empty());
        ArticleSummary summary = new ArticleSummary("H", "T", "D", "W", "36氪", "u1", DigestCategory.AI_FRONTIER);
        when(summarizationService.summarize(article)).thenReturn(summary);
        SummarizationService.EditorOutput editor = new SummarizationService.EditorOutput(
                "今日 AI", "开场", Map.of(DigestCategory.AI_FRONTIER, new SummarizationService.SectionLede("导语", List.of("u1"))));
        when(summarizationService.editor(any())).thenReturn(editor);

        DailyDigestService.DigestResult result = service.trigger();

        assertThat(result.executed()).isTrue();
        assertThat(result.articleCount()).isEqualTo(1);
        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxRepository).save(captor.capture());
        InboxItem saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo(InboxItemType.DIGEST);
        assertThat(saved.getSummary()).contains("AI 摘要");
        assertThat(saved.getLongText()).contains("\"aiGenerated\":true");
        ArgumentCaptor<DigestExecutionLog> logCaptor = ArgumentCaptor.forClass(DigestExecutionLog.class);
        verify(logRepository, atLeastOnce()).save(logCaptor.capture());
        DigestExecutionLog finalLog = logCaptor.getValue();
        assertThat(finalLog.getLlmCallCount()).isEqualTo(2); // 1 summarize + 1 editor
    }

    @Test
    void trigger_cacheHit_doesNotCountLlmCall() {
        stubEmptyLog();
        Article article = article("u1", DigestCategory.AI_FRONTIER);
        when(fetcherService.fetchAll()).thenReturn(List.of(article));
        when(classifier.classify(any())).thenReturn(DigestCategory.AI_FRONTIER);
        when(cacheRepository.findByLink("u1")).thenReturn(Optional.of(
                ArticleSummaryCache.builder().link("u1").headline("H").tldr("T").detail("D").whyItMatters("W").build()));
        SummarizationService.EditorOutput editor = new SummarizationService.EditorOutput(
                "今日 AI", "开场", Map.of(DigestCategory.AI_FRONTIER, new SummarizationService.SectionLede("导语", List.of("u1"))));
        when(summarizationService.editor(any())).thenReturn(editor);

        service.trigger();

        verify(summarizationService, never()).summarize(any());
        ArgumentCaptor<DigestExecutionLog> logCaptor = ArgumentCaptor.forClass(DigestExecutionLog.class);
        verify(logRepository, atLeastOnce()).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getLlmCallCount()).isEqualTo(1); // only editor
    }

    @Test
    void trigger_editorFails_fallsBackToKeywordVersion() {
        stubEmptyLog();
        Article article = article("u1", DigestCategory.AI_FRONTIER);
        when(fetcherService.fetchAll()).thenReturn(List.of(article));
        when(classifier.classify(any())).thenReturn(DigestCategory.AI_FRONTIER);
        when(cacheRepository.findByLink("u1")).thenReturn(Optional.empty());
        ArticleSummary summary = new ArticleSummary("H", "T", "D", "W", "36氪", "u1", DigestCategory.AI_FRONTIER);
        when(summarizationService.summarize(article)).thenReturn(summary);
        when(summarizationService.editor(any())).thenReturn(null);

        DailyDigestService.DigestResult result = service.trigger();

        assertThat(result.executed()).isTrue();
        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxRepository).save(captor.capture());
        InboxItem saved = captor.getValue();
        assertThat(saved.getSummary()).contains("AI 摘要暂不可用，已降级");
        assertThat(saved.getLongText()).doesNotContain("aiGenerated");
    }

    private void stubEmptyLog() {
        DigestExecutionLog logRow = DigestExecutionLog.builder()
                .digestDate(LocalDate.now())
                .status(com.esmile.axis.digest.store.DigestExecutionStatus.PENDING)
                .articleCount(0)
                .build();
        when(logRepository.findByDigestDate(any())).thenReturn(Optional.empty());
        when(logRepository.saveAndFlush(any())).thenReturn(logRow);
        when(logRepository.save(any())).thenReturn(logRow);
    }

    private Article article(String link, DigestCategory category) {
        return new Article("Title", link, "Desc", "36氪", Instant.now(), category);
    }
}
