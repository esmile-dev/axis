package com.esmile.axis.chat;

import com.esmile.axis.llm.AiConfigService;
import com.esmile.axis.llm.ChatGateway;
import com.esmile.axis.llm.LlmCallLogger;
import com.esmile.axis.chat.ChatConversationRepository;
import com.esmile.axis.chat.ChatLongMemoryRepository;
import com.esmile.axis.chat.ChatMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluation suite for LLM-generated conversation titles.
 *
 * <p>Reads golden first-messages from {@code evals/title-golden.json}, calls the
 * real LLM endpoint via {@link ChatHistoryService#generateTitle}, and asserts
 * non-blank rate (100%) and code-point length ≤ 20 pass rate (≥ 90%).
 *
 * <p>Skipped unless {@code AI_API_KEY} is present and not the default {@code demo}
 * value, so CI without a key does not fail.
 */
class TitleGenerationEval {

    private static final int TITLE_MAX_CODE_POINTS = 20;
    private static final double LENGTH_PASS_RATE = 0.90;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ChatHistoryService service;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("AI_API_KEY");
        String baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.openai.com");
        String model = System.getenv().getOrDefault("AI_MODEL", "gpt-4o-mini");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"demo".equals(apiKey),
                "Skipping TitleGenerationEval: set AI_API_KEY env var to a real key");

        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().options(opts).build();
        ChatClient chatClient = ChatClient.create(chatModel);

        AiConfigService aiConfigService = Mockito.mock(AiConfigService.class);
        Mockito.when(aiConfigService.get()).thenReturn(chatClient);

        service = new ChatHistoryService(
                Mockito.mock(ChatConversationRepository.class),
                Mockito.mock(ChatMessageRepository.class),
                Mockito.mock(ChatLongMemoryRepository.class),
                new ChatGateway(aiConfigService, Mockito.mock(ChatMemory.class), Mockito.mock(LlmCallLogger.class)));
    }

    @Test
    void goldenCases() throws Exception {
        List<Map<String, String>> cases;
        try (InputStream is = getClass().getResourceAsStream("/evals/title-golden.json")) {
            assertThat(is).isNotNull();
            cases = MAPPER.readValue(is, new TypeReference<>() { });
        }
        assertThat(cases).hasSizeGreaterThanOrEqualTo(15);

        int lengthOk = 0;
        int total = 0;

        for (Map<String, String> c : cases) {
            String title = service.generateTitle(c.get("message"));

            assertThat(title).as("title should not be blank for: %s", c.get("message")).isNotBlank();
            assertThat(title).as("title should be single line").doesNotContain("\n");

            long len = title.codePoints().count();
            System.out.printf("  [%2d] %s  =>  %s%n", len, abbreviate(c.get("message")), title);
            if (len <= TITLE_MAX_CODE_POINTS) lengthOk++;
            total++;
        }

        double lengthRate = (double) lengthOk / total;
        System.out.printf("TitleGenerationEval: total=%d length(≤%d) pass=%.2f%n",
                total, TITLE_MAX_CODE_POINTS, lengthRate);

        assertThat(lengthRate).as("length pass rate ≥ %.0f%%", LENGTH_PASS_RATE * 100)
                .isGreaterThanOrEqualTo(LENGTH_PASS_RATE);
    }

    private static String abbreviate(String message) {
        return message.length() <= 24 ? message : message.substring(0, 24) + "…";
    }
}
