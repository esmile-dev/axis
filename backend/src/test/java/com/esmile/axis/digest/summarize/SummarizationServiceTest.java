package com.esmile.axis.digest.summarize;

import com.esmile.axis.llm.ChatGateway;
import com.esmile.axis.inbox.DigestCategory;
import com.esmile.axis.digest.fetch.Article;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SummarizationService}: cache hit, structured-output success/fallback,
 * retry on unparseable output, null-field defaults, and editor pass.
 * LLM 调用经 {@link ChatGateway} stub（链式 mock 只保留在 {@code ChatGatewayTest}）。
 */
@ExtendWith(MockitoExtension.class)
class SummarizationServiceTest {

    @Mock
    private ChatGateway chatGateway;

    @Mock
    private ArticleSummaryCacheRepository cacheRepository;

    private SummarizationService service;

    @BeforeEach
    void setUp() {
        service = new SummarizationService(chatGateway, cacheRepository);
    }

    private void mockLlmEntity(Object entity) {
        doReturn(entity).when(chatGateway).callEntity(any(String.class), any(Class.class), any());
    }

    @Test
    void summarize_cacheHit_returnsCachedWithoutLlm() {
        Article article = article("u1", "Title", "desc");
        when(cacheRepository.findByLink("u1")).thenReturn(Optional.of(
                ArticleSummaryCache.builder()
                        .link("u1").headline("H").tldr("T").detail("D").whyItMatters("W").build()));

        ArticleSummary s = service.summarize(article);

        assertThat(s.headline()).isEqualTo("H");
        assertThat(s.tldr()).isEqualTo("T");
        verifyNoInteractions(chatGateway);
    }

    @Test
    void summarize_llmSuccess_bindsEntityAndCaches() {
        Article article = article("u2", "Title", "desc");
        when(cacheRepository.findByLink("u2")).thenReturn(Optional.empty());
        mockLlmEntity(new SummarizationService.LlmArticleSummary(
                "中文标题", "一句话", "两句事实", "因为重要", "36氪", "u2"));

        ArticleSummary s = service.summarize(article);

        assertThat(s.headline()).isEqualTo("中文标题");
        assertThat(s.whyItMatters()).isEqualTo("因为重要");
        assertThat(s.category()).isEqualTo(DigestCategory.AI_FRONTIER);
        verify(cacheRepository).save(any(ArticleSummaryCache.class));
    }

    @Test
    void summarize_llmOutputNullFields_defaultsApplied() {
        Article article = article("u5", "Title", "desc");
        when(cacheRepository.findByLink("u5")).thenReturn(Optional.empty());
        mockLlmEntity(new SummarizationService.LlmArticleSummary(null, null, null, null, null, null));

        ArticleSummary s = service.summarize(article);

        assertThat(s.headline()).isEmpty();
        assertThat(s.source()).isEqualTo("36氪");
        assertThat(s.url()).isEqualTo("u5");
    }

    @Test
    void summarize_llmOutputUnparseable_retriesThenFallbacks() {
        Article article = article("u3", "Title", "bad");
        when(cacheRepository.findByLink("u3")).thenReturn(Optional.empty());
        when(chatGateway.callEntity(any(String.class), any(Class.class), any()))
                .thenThrow(new RuntimeException("structured output conversion failed"));

        ArticleSummary s = service.summarize(article);

        assertThat(s.tldr()).isEqualTo("bad");
        assertThat(s.whyItMatters()).contains("降级");
        verify(chatGateway, times(3)).callEntity(any(String.class), any(Class.class), any()); // initial + MAX_RETRIES(2)
    }

    @Test
    void summarize_llmThrows_returnsFallback() {
        Article article = article("u4", "Title", "desc");
        when(cacheRepository.findByLink("u4")).thenReturn(Optional.empty());
        when(chatGateway.callEntity(any(String.class), any(Class.class), any()))
                .thenThrow(new RuntimeException("timeout"));

        ArticleSummary s = service.summarize(article);

        assertThat(s.whyItMatters()).contains("降级");
    }

    @Test
    void editor_llmSuccess_returnsEditorOutput() {
        ArticleSummary s = new ArticleSummary("H", "T", "D", "W", "36氪", "u", DigestCategory.AI_FRONTIER);
        Map<DigestCategory, List<ArticleSummary>> sectioned = Map.of(DigestCategory.AI_FRONTIER, List.of(s));
        mockLlmEntity(new SummarizationService.EditorJson(
                "今日 AI", "开场",
                new SummarizationService.EditorSections(
                        new SummarizationService.EditorSection("导语", List.of("u")),
                        null, null, null)));

        SummarizationService.EditorOutput out = service.editor(sectioned);

        assertThat(out).isNotNull();
        assertThat(out.headline()).isEqualTo("今日 AI");
        assertThat(out.sectionLedes()).containsKey(DigestCategory.AI_FRONTIER);
        assertThat(out.sectionLedes().get(DigestCategory.AI_FRONTIER).articleOrder()).containsExactly("u");
        assertThat(out.sectionLedes()).doesNotContainKey(DigestCategory.OTHER);
    }

    @Test
    void editor_llmFailure_returnsNull() {
        ArticleSummary s = new ArticleSummary("H", "T", "D", "W", "36氪", "u", DigestCategory.AI_FRONTIER);
        Map<DigestCategory, List<ArticleSummary>> sectioned = Map.of(DigestCategory.AI_FRONTIER, List.of(s));
        when(chatGateway.callEntity(any(String.class), any(Class.class), any()))
                .thenThrow(new RuntimeException("boom"));

        assertThat(service.editor(sectioned)).isNull();
    }

    private Article article(String link, String title, String desc) {
        return new Article(title, link, desc, "36氪", Instant.now(), DigestCategory.AI_FRONTIER);
    }
}
