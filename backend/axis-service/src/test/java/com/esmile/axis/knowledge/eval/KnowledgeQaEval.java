package com.esmile.axis.knowledge.eval;

import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.knowledge.ArtifactKind;
import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.chat.KnowledgeQaService;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeArtifactRepository;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluation suite for knowledge item QA (NFR-005, FR-009).
 *
 * <p>Reads ≥5 QA rows from {@code /evals/knowledge/qa-golden.jsonl} (the docs/ golden
 * file, mapped onto the test classpath via pom testResources), joining article bodies
 * from {@code summary-golden.jsonl}. Each row runs the real QA chain
 * ({@link KnowledgeQaService}: system prompt build + per-item ChatMemory under
 * {@code knowledge-}{itemId} + LLM, no tools) and is then graded by an LLM judge
 * ("answer grounded in the source and covers the keyPoints", contract in
 * {@code evals/qa-judge.md}; verdict parsed by {@link QaJudgeParser}).
 * PASS rate must be ≥80%. The golden set includes ≥1 out-of-scope question whose
 * keyPoints require an explicit 「原文未提及」 refusal — it counts toward the rate.
 *
 * <p>All rows share one ChatMemory instance, so a conversationId wiring bug (e.g. a
 * constant id) would leak one article's context into another row and fail the judge.
 *
 * <p>Skipped unless {@code AI_API_KEY} is present and not the default {@code demo}
 * value. Set {@code KNOWLEDGE_EVAL_LIMIT} to run only the first N rows, or
 * {@code KNOWLEDGE_EVAL_IDS} (comma-separated articleIds) to run specific rows
 * (cheap prompt iteration).
 */
class KnowledgeQaEval {

    private static final double PASS_RATE = 0.80;

    /** Judge input truncation: keeps judge cost bounded on long articles. */
    private static final int JUDGE_CONTENT_MAX_CHARS = 20_000;

    /** QA answer collection bound: service LLM timeout is 60s; leave headroom. */
    private static final Duration QA_BLOCK_TIMEOUT = Duration.ofSeconds(90);

    /** Judge prompt — contract documented verbatim in evals/qa-judge.md. */
    private static final String JUDGE_PROMPT = """
            你是严格的技术内容评测专家。给定一篇文章的标题、正文、一个用户问题、参考答案要点和一份 AI 生成的答案，请判定该答案是否合格。

            判定标准（同时满足才 PASS）：
            - 源自原文：答案中的事实、数据、结论均来自所给原文，无编造、无外部知识
            - 覆盖要点：答案覆盖了全部参考答案要点
            - 若要点要求「明确表示原文未提及」（超纲问题）：答案明确说明原文未提及且未强行作答，即视为覆盖

            只输出两行：
            VERDICT: <PASS 或 FAIL>
            REASON: <一句话理由>

            文章标题：{title}
            文章正文：
            {content}

            用户问题：{question}

            参考答案要点：
            {keyPoints}

            待评答案：
            {answer}
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ChatClient chatClient;
    private static AiConfigService aiConfigService;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("AI_API_KEY");
        String baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.openai.com");
        String model = System.getenv().getOrDefault("AI_MODEL", "gpt-4o-mini");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"demo".equals(apiKey),
                "Skipping KnowledgeQaEval: set AI_API_KEY env var to a real key");

        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().options(opts).build();
        chatClient = ChatClient.create(chatModel);

        aiConfigService = Mockito.mock(AiConfigService.class);
        Mockito.when(aiConfigService.get()).thenReturn(chatClient);
    }

    record QaRow(String articleId, String question, List<String> keyPoints) {
    }

    record GoldenArticle(String id, String title, String content) {
    }

    @Test
    void goldenCases() throws Exception {
        int limit = evalLimit();
        List<QaRow> rows = loadQaGolden(limit);
        // Full runs require ≥5 QA rows; KNOWLEDGE_EVAL_IDS/LIMIT subset runs only require a non-empty selection.
        if (System.getenv("KNOWLEDGE_EVAL_IDS") != null || limit != Integer.MAX_VALUE) {
            assertThat(rows).isNotEmpty();
        } else {
            assertThat(rows).hasSizeGreaterThanOrEqualTo(5);
        }
        Map<String, GoldenArticle> articles = loadArticles();

        // 全行共享一个 ChatMemory：conversationId 接线错误会让条目间上下文互串，judge 会判 FAIL
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(100)
                .build();

        int passed = 0;
        for (QaRow row : rows) {
            GoldenArticle article = articles.get(row.articleId());
            assertThat(article).as("article %s present in summary-golden.jsonl", row.articleId()).isNotNull();

            String answer = runQa(chatMemory, article, row.question());
            QaJudgeParser.Verdict verdict = answer.isBlank()
                    ? new QaJudgeParser.Verdict(false, "QA answer empty")
                    : judge(article, row, answer);
            if (verdict.pass()) {
                passed++;
            }
            System.out.printf("[%s] verdict=%s reason=%s | Q: %s | A: %s%n",
                    row.articleId(), verdict.pass() ? "PASS" : "FAIL", verdict.reason(),
                    row.question(), abbreviate(answer));
        }

        int total = rows.size();
        double passRate = (double) passed / total;
        System.out.printf("KnowledgeQaEval: total=%d pass=%d/%d (%.2f, 阈值 %.2f)%n",
                total, passed, total, passRate, PASS_RATE);

        assertThat(passRate).as("QA judge pass rate ≥ %.0f%%", PASS_RATE * 100)
                .isGreaterThanOrEqualTo(PASS_RATE);
    }

    /** Real QA chain: repository lookup → system prompt → ChatMemory advisor → streamed LLM answer. */
    private String runQa(MessageWindowChatMemory chatMemory, GoldenArticle article, String question) {
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.ARTICLE)
                .title(article.title())
                .content(article.content())
                .build();
        item.setId(article.id());

        KnowledgeItemRepository itemRepository = Mockito.mock(KnowledgeItemRepository.class);
        KnowledgeArtifactRepository artifactRepository = Mockito.mock(KnowledgeArtifactRepository.class);
        Mockito.when(itemRepository.findById(article.id())).thenReturn(Optional.of(item));
        Mockito.when(artifactRepository.findByItemIdAndKind(article.id(), ArtifactKind.SUMMARY))
                .thenReturn(Optional.empty()); // golden 无预置总结，走原文-only prompt 分支

        KnowledgeQaService service =
                new KnowledgeQaService(itemRepository, artifactRepository, aiConfigService, chatMemory);
        List<String> tokens = service.chat(article.id(), question).collectList().block(QA_BLOCK_TIMEOUT);
        return tokens == null ? "" : String.join("", tokens).strip();
    }

    /** LLM judge call: 120s read timeout + one retry on error/unparsable, so a transient API hiccup cannot crash the run. */
    private QaJudgeParser.Verdict judge(GoldenArticle article, QaRow row, String answer) {
        String content = article.content();
        if (content.length() > JUDGE_CONTENT_MAX_CHARS) {
            content = content.substring(0, JUDGE_CONTENT_MAX_CHARS)
                    + "\n（注：正文过长已截断，以上仅前 " + JUDGE_CONTENT_MAX_CHARS + " 字符）";
        }
        String keyPoints = row.keyPoints().stream().map(p -> "- " + p)
                .collect(java.util.stream.Collectors.joining("\n"));
        String prompt = JUDGE_PROMPT
                .replace("{title}", article.title())
                .replace("{content}", content)
                .replace("{question}", row.question())
                .replace("{keyPoints}", keyPoints)
                .replace("{answer}", answer);
        for (int attempt = 1; attempt <= 2; attempt++) {
            String text;
            try {
                text = chatClient.prompt().user(prompt)
                        .options(OpenAiChatOptions.builder().timeout(Duration.ofSeconds(120)))
                        .call().content();
            } catch (Exception e) {
                System.out.printf("[%s] judge call failed (attempt %d): %s%n", row.articleId(), attempt, e);
                continue;
            }
            Optional<QaJudgeParser.Verdict> verdict = QaJudgeParser.parse(text);
            if (verdict.isPresent()) {
                return verdict.get();
            }
            System.out.printf("[%s] judge verdict unparsable (attempt %d), raw response: %s%n",
                    row.articleId(), attempt, text);
        }
        return new QaJudgeParser.Verdict(false, "judge failed or unparsable after 2 attempts");
    }

    private static int evalLimit() {
        return Optional.ofNullable(System.getenv("KNOWLEDGE_EVAL_LIMIT"))
                .filter(s -> !s.isBlank()).map(Integer::parseInt).orElse(Integer.MAX_VALUE);
    }

    /** Golden files live in docs/ and are mapped onto the test classpath via pom testResources. */
    private static List<QaRow> loadQaGolden(int limit) throws IOException {
        String jsonl = readClasspath("/evals/knowledge/qa-golden.jsonl");
        Set<String> ids = Optional.ofNullable(System.getenv("KNOWLEDGE_EVAL_IDS"))
                .filter(s -> !s.isBlank())
                .map(s -> Arrays.stream(s.split(",")).map(String::strip).collect(java.util.stream.Collectors.toSet()))
                .orElse(Set.of());
        List<QaRow> rows = new ArrayList<>();
        for (String line : jsonl.split("\n")) {
            if (line.isBlank() || rows.size() >= limit) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = MAPPER.readValue(line, Map.class);
            String articleId = (String) row.get("articleId");
            if (!ids.isEmpty() && !ids.contains(articleId)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<String> keyPoints = (List<String>) row.get("keyPoints");
            rows.add(new QaRow(articleId, (String) row.get("question"), keyPoints));
        }
        return rows;
    }

    private static Map<String, GoldenArticle> loadArticles() throws IOException {
        String jsonl = readClasspath("/evals/knowledge/summary-golden.jsonl");
        Map<String, GoldenArticle> articles = new java.util.HashMap<>();
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, String> row = MAPPER.readValue(line, Map.class);
            articles.put(row.get("id"), new GoldenArticle(row.get("id"), row.get("title"), row.get("content")));
        }
        return articles;
    }

    private static String readClasspath(String path) throws IOException {
        try (InputStream is = KnowledgeQaEval.class.getResourceAsStream(path)) {
            assertThat(is).as(path + " on test classpath").isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String abbreviate(String text) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }
}
