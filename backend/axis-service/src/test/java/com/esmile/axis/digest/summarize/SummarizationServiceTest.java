package com.esmile.axis.digest.summarize;

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
 * Unit tests for {@link SummarizationService}: cache hit, LLM success/fallback,
 * JSON retry, and editor pass.
 */
@ExtendWith(MockitoExtension.class)
class SummarizationServiceTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ArticleSummaryCacheRepository cacheRepository;

    private SummarizationService service;

    @BeforeEach
    void setUp() {
        service = new SummarizationService(chatClient, cacheRepository);
    }

    private ChatClient.ChatClientRequestSpec mockLlmResponse(String response) {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec resp = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(resp);
        when(resp.content()).thenReturn(response);
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
        verify(chatClient, never()).prompt();
    }

    @Test
    void summarize_llmSuccess_parsesJsonAndCaches() {
        Article article = article("u2", "Title", "desc");
        when(cacheRepository.findByLink("u2")).thenReturn(Optional.empty());
        mockLlmResponse("""
                {"headline":"中文标题","tldr":"一句话","detail":"两句事实","why_it_matters":"因为重要","source":"36氪","url":"u2"}
                """);

        ArticleSummary s = service.summarize(article);

        assertThat(s.headline()).isEqualTo("中文标题");
        assertThat(s.whyItMatters()).isEqualTo("因为重要");
        verify(cacheRepository).save(any(ArticleSummaryCache.class));
    }

    @Test
    void summarize_llmReturnsBadJson_retriesOnceThenFallbacks() {
        Article article = article("u3", "Title", "bad");
        when(cacheRepository.findByLink("u3")).thenReturn(Optional.empty());
        ChatClient.ChatClientRequestSpec spec = mockLlmResponse("not json");

        ArticleSummary s = service.summarize(article);

        assertThat(s.tldr()).isEqualTo("bad");
        assertThat(s.whyItMatters()).contains("降级");
        verify(spec, times(2)).call(); // initial + one retry
    }

    @Test
    void summarize_llmThrows_returnsFallback() {
        Article article = article("u4", "Title", "desc");
        when(cacheRepository.findByLink("u4")).thenReturn(Optional.empty());
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
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
        mockLlmResponse("""
                {"headline":"今日 AI","opening":"开场","sections":{"ai":{"lede":"导语","articleOrder":["u"]}}}
                """);

        SummarizationService.EditorOutput out = service.editor(sectioned);

        assertThat(out).isNotNull();
        assertThat(out.headline()).isEqualTo("今日 AI");
        assertThat(out.sectionLedes()).containsKey(DigestCategory.AI_FRONTIER);
    }

    @Test
    void editor_llmFailure_returnsNull() {
        ArticleSummary s = new ArticleSummary("H", "T", "D", "W", "36氪", "u", DigestCategory.AI_FRONTIER);
        Map<DigestCategory, List<ArticleSummary>> sectioned = Map.of(DigestCategory.AI_FRONTIER, List.of(s));
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
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
