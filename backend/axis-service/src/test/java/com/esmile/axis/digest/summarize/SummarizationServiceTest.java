package com.esmile.axis.digest.summarize;

import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.digest.classify.DigestCategory;
import com.esmile.axis.digest.fetch.Article;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

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
 */
@ExtendWith(MockitoExtension.class)
class SummarizationServiceTest {

    @Mock
    private AiConfigService aiConfigService;

    @Mock
    private ArticleSummaryCacheRepository cacheRepository;

    private SummarizationService service;

    @BeforeEach
    void setUp() {
        service = new SummarizationService(aiConfigService, cacheRepository);
    }

    private ChatClient.ChatClientRequestSpec mockLlmEntity(Object entity) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec resp = mock(ChatClient.CallResponseSpec.class);
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(resp);
        doReturn(entity).when(resp).entity(any(Class.class));
        return spec;
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
        verify(aiConfigService, never()).get();
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
    void summarize_llmOutputUnparseable_retriesOnceThenFallbacks() {
        Article article = article("u3", "Title", "bad");
        when(cacheRepository.findByLink("u3")).thenReturn(Optional.empty());
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec resp = mock(ChatClient.CallResponseSpec.class);
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(resp);
        when(resp.entity(any(Class.class))).thenThrow(new RuntimeException("structured output conversion failed"));

        ArticleSummary s = service.summarize(article);

        assertThat(s.tldr()).isEqualTo("bad");
        assertThat(s.whyItMatters()).contains("降级");
        verify(spec, times(2)).call(); // initial + one retry
    }

    @Test
    void summarize_llmThrows_returnsFallback() {
        Article article = article("u4", "Title", "desc");
        when(cacheRepository.findByLink("u4")).thenReturn(Optional.empty());
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenThrow(new RuntimeException("timeout"));

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
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(aiConfigService.get()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenThrow(new RuntimeException("boom"));

        assertThat(service.editor(sectioned)).isNull();
    }

    private Article article(String link, String title, String desc) {
        return new Article(title, link, desc, "36氪", Instant.now(), DigestCategory.AI_FRONTIER);
    }
}
