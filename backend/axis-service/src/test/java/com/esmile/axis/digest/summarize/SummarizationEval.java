package com.esmile.axis.digest.summarize;

import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.digest.classify.DigestCategory;
import com.esmile.axis.digest.fetch.Article;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Evaluation suite for the LLM fine-read summarizer.
 *
 * <p>Reads 20 golden cases from {@code evals/summarize-golden.jsonl}, calls the
 * real LLM endpoint, and asserts schema completeness and Chinese length thresholds.
 *
 * <p>Skipped unless {@code AI_API_KEY} is present and not the default {@code demo}
 * value, so CI without a key does not fail.
 */
class SummarizationEval {

    private static final double WHY_IT_MATTERS_MIN_LENGTH = 20;
    private static final double WHY_IT_MATTERS_MAX_LENGTH = 100;
    private static final double WHY_IT_MATTERS_PASS_RATE = 0.85;

    private static final double HEADLINE_MIN_LENGTH = 5;
    private static final double HEADLINE_MAX_LENGTH = 20;
    private static final double HEADLINE_PASS_RATE = 0.90;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SummarizationService service;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("AI_API_KEY");
        String baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.openai.com");
        String model = System.getenv().getOrDefault("AI_MODEL", "gpt-4o-mini");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"demo".equals(apiKey),
                "Skipping SummarizationEval: set AI_API_KEY env var to a real key");

        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().options(opts).build();
        ChatClient chatClient = ChatClient.create(chatModel);

        AiConfigService aiConfigService = Mockito.mock(AiConfigService.class);
        Mockito.when(aiConfigService.get()).thenReturn(chatClient);

        ArticleSummaryCacheRepository cacheRepo = Mockito.mock(ArticleSummaryCacheRepository.class);
        when(cacheRepo.save(any(ArticleSummaryCache.class))).thenAnswer(i -> i.getArgument(0));
        when(cacheRepo.findByLink(any())).thenReturn(Optional.empty());

        service = new SummarizationService(aiConfigService, cacheRepo);
    }

    @Test
    void goldenCases() throws Exception {
        List<Map<String, Object>> cases = new ArrayList<>();
        try (InputStream is = getClass().getResourceAsStream("/evals/summarize-golden.jsonl")) {
            assertThat(is).isNotNull();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        cases.add(MAPPER.readValue(line, new TypeReference<>() { }));
                    }
                }
            }
        }
        assertThat(cases).hasSizeGreaterThanOrEqualTo(20);

        int whyOk = 0;
        int headlineOk = 0;
        int total = 0;

        for (Map<String, Object> c : cases) {
            @SuppressWarnings("unchecked")
            Map<String, String> input = (Map<String, String>) c.get("input");
            Article article = new Article(
                    input.get("title"),
                    input.get("link"),
                    input.get("description"),
                    input.get("sourceName"),
                    Instant.now(),
                    DigestCategory.AI_FRONTIER
            );
            ArticleSummary s = service.summarize(article);

            assertThat(s.headline()).as("headline should not be blank").isNotBlank();
            assertThat(s.tldr()).as("tldr should not be blank").isNotBlank();
            assertThat(s.detail()).as("detail should not be blank").isNotBlank();
            assertThat(s.whyItMatters()).as("why_it_matters should not be blank").isNotBlank();
            assertThat(s.source()).as("source should not be blank").isNotBlank();
            assertThat(s.url()).as("url should not be blank").isNotBlank();

            double whyLen = s.whyItMatters().length();
            if (whyLen >= WHY_IT_MATTERS_MIN_LENGTH && whyLen <= WHY_IT_MATTERS_MAX_LENGTH) whyOk++;

            double headlineLen = s.headline().length();
            if (headlineLen >= HEADLINE_MIN_LENGTH && headlineLen <= HEADLINE_MAX_LENGTH) headlineOk++;

            total++;
        }

        double whyRate = (double) whyOk / total;
        double headlineRate = (double) headlineOk / total;

        System.out.printf("SummarizationEval: total=%d why_it_matters pass=%.2f headline pass=%.2f%n",
                total, whyRate, headlineRate);

        assertThat(whyRate).as("why_it_matters length pass rate ≥ %.0f%%", WHY_IT_MATTERS_PASS_RATE * 100)
                .isGreaterThanOrEqualTo(WHY_IT_MATTERS_PASS_RATE);
        assertThat(headlineRate).as("headline length pass rate ≥ %.0f%%", HEADLINE_PASS_RATE * 100)
                .isGreaterThanOrEqualTo(HEADLINE_PASS_RATE);
    }
}
