package com.esmile.axis.knowledge.eval;

import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.knowledge.ArtifactKind;
import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.entity.KnowledgeArtifact;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.generate.KnowledgeArtifactGenerator;
import com.esmile.axis.knowledge.repository.KnowledgeArtifactRepository;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Evaluation suite for knowledge summary/mindmap generation (NFR-003/004).
 *
 * <p>Reads ≥10 real articles from {@code /evals/knowledge/summary-golden.jsonl} (the
 * docs/ golden file, mapped onto the test classpath via pom testResources), runs each
 * through the real pipeline
 * ({@link KnowledgeArtifactGenerator} with mocked repositories), and asserts:
 * summary structure pass rate 100% (script), judge relevance ≥4/5 share ≥80% (LLM-as-judge,
 * contract in {@code evals/summary-judge.md}), mindmap legality pass rate ≥90% (script).
 *
 * <p>Skipped unless {@code AI_API_KEY} is present and not the default {@code demo}
 * value, so CI without a key does not fail. Set {@code KNOWLEDGE_EVAL_LIMIT} to run
 * only the first N articles, or {@code KNOWLEDGE_EVAL_IDS} (comma-separated) to run
 * specific articles (cheap prompt iteration).
 */
class KnowledgeSummaryEval {

    private static final double STRUCTURE_PASS_RATE = 1.00;
    private static final double RELEVANCE_PASS_RATE = 0.80;
    private static final double MINDMAP_PASS_RATE = 0.90;
    private static final int RELEVANCE_PASS_SCORE = 4;

    /** Judge input truncation: keeps judge cost bounded on long articles. */
    private static final int JUDGE_CONTENT_MAX_CHARS = 20_000;

    /** Judge prompt — contract documented verbatim in evals/summary-judge.md. */
    private static final String JUDGE_PROMPT = """
            你是严格的技术内容评测专家。给定一篇文章的标题、正文和一份 AI 生成的总结，请对总结评分。

            评分维度（综合为 1-5 分）：
            - 忠实原文：总结中的事实、数据、结论均来自原文，无编造
            - 抓住主旨：TL;DR 与要点覆盖文章核心观点，而非细枝末节
            - 结构完整：包含 TL;DR / 要点 / 关键洞察 三节

            评分标准：
            5 = 完全忠实、主旨清晰、可直接发布
            4 = 基本忠实，有轻微遗漏或表述偏差，无编造
            3 = 部分要点遗漏或一处轻微失实
            2 = 多处失实或严重遗漏
            1 = 大量编造或答非所问

            只输出两行：
            SCORE: <1-5 的整数>
            REASON: <一句话理由>

            文章标题：{title}
            文章正文：
            {content}

            待评总结：
            {summary}
            """;

    private static final Pattern SCORE_PATTERN = Pattern.compile("(?i)SCORE\\s*[:：]\\s*([1-5])");
    private static final Pattern REASON_PATTERN = Pattern.compile("(?im)^REASON\\s*[:：]\\s*(.+?)\\s*$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ChatClient chatClient;
    private static AiConfigService aiConfigService;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("AI_API_KEY");
        String baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.openai.com");
        String model = System.getenv().getOrDefault("AI_MODEL", "gpt-4o-mini");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"demo".equals(apiKey),
                "Skipping KnowledgeSummaryEval: set AI_API_KEY env var to a real key");

        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().options(opts).build();
        chatClient = ChatClient.create(chatModel);

        aiConfigService = Mockito.mock(AiConfigService.class);
        Mockito.when(aiConfigService.get()).thenReturn(chatClient);
        Mockito.when(aiConfigService.getConfig()).thenReturn(
                new AiConfigService.ResolvedConfig(null, "eval", "key", baseUrl, model, "env"));
    }

    record GoldenArticle(String id, String title, String content, String lang) {
    }

    record JudgeResult(int score, String reason) {
    }

    @Test
    void goldenCases() throws Exception {
        int limit = evalLimit();
        List<GoldenArticle> articles = loadGolden(limit);
        // Full runs require ≥10 articles; KNOWLEDGE_EVAL_IDS/LIMIT subset runs only require a non-empty selection.
        if (System.getenv("KNOWLEDGE_EVAL_IDS") != null || limit != Integer.MAX_VALUE) {
            assertThat(articles).isNotEmpty();
        } else {
            assertThat(articles).hasSizeGreaterThanOrEqualTo(10);
        }

        int structureOk = 0;
        int relevanceOk = 0;
        int mindmapOk = 0;

        for (GoldenArticle article : articles) {
            Map<ArtifactKind, KnowledgeArtifact> artifacts = runPipeline(article);
            KnowledgeArtifact summary = artifacts.get(ArtifactKind.SUMMARY);
            KnowledgeArtifact mindmap = artifacts.get(ArtifactKind.MINDMAP);

            ArtifactChecks.CheckResult structure = ArtifactChecks.summaryStructure(summary.getContent());
            ArtifactChecks.CheckResult legality = ArtifactChecks.mindmapLegality(mindmap.getContent());
            JudgeResult judged = summary.getContent().isBlank()
                    ? new JudgeResult(0, "summary generation failed") : judge(article, summary.getContent());

            if (structure.ok()) structureOk++;
            if (judged.score() >= RELEVANCE_PASS_SCORE) relevanceOk++;
            if (legality.ok()) mindmapOk++;

            System.out.printf("[%s] structure=%s relevance=%d mindmap=%s%s%s%s%s reason=%s%n",
                    article.id(),
                    structure.ok() ? "OK" : "FAIL",
                    judged.score(),
                    legality.ok() ? "OK" : "FAIL",
                    structure.ok() ? "" : " " + structure.violations(),
                    legality.ok() ? "" : " " + legality.violations(),
                    summary.getError() != null ? " summaryError=" + summary.getError() : "",
                    mindmap.getError() != null ? " mindmapError=" + mindmap.getError() : "",
                    judged.reason());
        }

        int total = articles.size();
        double structureRate = (double) structureOk / total;
        double relevanceRate = (double) relevanceOk / total;
        double mindmapRate = (double) mindmapOk / total;

        System.out.printf(
                "KnowledgeSummaryEval: total=%d summaryStructure=%d/%d (%.2f, 阈值 %.2f)"
                        + " relevance>=%d=%d/%d (%.2f, 阈值 %.2f) mindmapLegal=%d/%d (%.2f, 阈值 %.2f)%n",
                total, structureOk, total, structureRate, STRUCTURE_PASS_RATE,
                RELEVANCE_PASS_SCORE, relevanceOk, total, relevanceRate, RELEVANCE_PASS_RATE,
                mindmapOk, total, mindmapRate, MINDMAP_PASS_RATE);

        assertThat(structureRate).as("summary structure pass rate = %.0f%%", STRUCTURE_PASS_RATE * 100)
                .isGreaterThanOrEqualTo(STRUCTURE_PASS_RATE);
        assertThat(relevanceRate).as("judge relevance ≥ %d share ≥ %.0f%%",
                        RELEVANCE_PASS_SCORE, RELEVANCE_PASS_RATE * 100)
                .isGreaterThanOrEqualTo(RELEVANCE_PASS_RATE);
        assertThat(mindmapRate).as("mindmap legality pass rate ≥ %.0f%%", MINDMAP_PASS_RATE * 100)
                .isGreaterThanOrEqualTo(MINDMAP_PASS_RATE);
    }

    /** Real pipeline: prompt build → LLM call → stripCodeFence → artifact, via the generator. */
    private Map<ArtifactKind, KnowledgeArtifact> runPipeline(GoldenArticle article) {
        KnowledgeItem item = KnowledgeItem.builder()
                .type(KnowledgeType.ARTICLE)
                .title(article.title())
                .content(article.content())
                .build();
        item.setId(article.id());

        KnowledgeItemRepository itemRepository = Mockito.mock(KnowledgeItemRepository.class);
        KnowledgeArtifactRepository artifactRepository = Mockito.mock(KnowledgeArtifactRepository.class);
        when(itemRepository.findById(article.id())).thenReturn(Optional.of(item));
        when(artifactRepository.findByItemIdAndKind(eq(article.id()), any())).thenReturn(Optional.empty());
        List<KnowledgeArtifact> saved = new ArrayList<>();
        when(artifactRepository.save(any(KnowledgeArtifact.class))).thenAnswer(inv -> {
            KnowledgeArtifact a = inv.getArgument(0);
            saved.add(a);
            return a;
        });

        KnowledgeArtifactGenerator generator =
                new KnowledgeArtifactGenerator(itemRepository, artifactRepository, aiConfigService);
        generator.generateAll(article.id()); // direct call runs synchronously (no Spring proxy)

        Map<ArtifactKind, KnowledgeArtifact> byKind = new java.util.EnumMap<>(ArtifactKind.class);
        for (KnowledgeArtifact a : saved) {
            byKind.put(a.getKind(), a);
        }
        byKind.putIfAbsent(ArtifactKind.SUMMARY,
                KnowledgeArtifact.builder().item(item).kind(ArtifactKind.SUMMARY).content("").error("no artifact saved").build());
        byKind.putIfAbsent(ArtifactKind.MINDMAP,
                KnowledgeArtifact.builder().item(item).kind(ArtifactKind.MINDMAP).content("").error("no artifact saved").build());
        return byKind;
    }

    /** LLM judge call: 120s read timeout + one retry on error/unparsable, so a transient API hiccup cannot crash the run. */
    private JudgeResult judge(GoldenArticle article, String summary) {
        String content = article.content();
        if (content.length() > JUDGE_CONTENT_MAX_CHARS) {
            content = content.substring(0, JUDGE_CONTENT_MAX_CHARS)
                    + "\n（注：正文过长已截断，以上仅前 " + JUDGE_CONTENT_MAX_CHARS + " 字符）";
        }
        String prompt = JUDGE_PROMPT
                .replace("{title}", article.title())
                .replace("{content}", content)
                .replace("{summary}", summary);
        for (int attempt = 1; attempt <= 2; attempt++) {
            String text;
            try {
                text = chatClient.prompt().user(prompt)
                        .options(OpenAiChatOptions.builder().timeout(Duration.ofSeconds(120)))
                        .call().content();
            } catch (Exception e) {
                System.out.printf("[%s] judge call failed (attempt %d): %s%n", article.id(), attempt, e);
                continue;
            }
            Matcher m = SCORE_PATTERN.matcher(text == null ? "" : text);
            if (m.find()) {
                Matcher r = REASON_PATTERN.matcher(text);
                return new JudgeResult(Integer.parseInt(m.group(1)), r.find() ? r.group(1) : "");
            }
            System.out.printf("[%s] judge score unparsable (attempt %d), raw response: %s%n", article.id(), attempt, text);
        }
        return new JudgeResult(0, "judge failed or unparsable after 2 attempts");
    }

    private static int evalLimit() {
        return Optional.ofNullable(System.getenv("KNOWLEDGE_EVAL_LIMIT"))
                .filter(s -> !s.isBlank()).map(Integer::parseInt).orElse(Integer.MAX_VALUE);
    }

    /** Golden file lives in docs/ and is mapped onto the test classpath via pom testResources. */
    private static List<GoldenArticle> loadGolden(int limit) throws IOException {
        String jsonl;
        try (InputStream is = KnowledgeSummaryEval.class.getResourceAsStream("/evals/knowledge/summary-golden.jsonl")) {
            assertThat(is).as("/evals/knowledge/summary-golden.jsonl on test classpath").isNotNull();
            jsonl = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> ids = Optional.ofNullable(System.getenv("KNOWLEDGE_EVAL_IDS"))
                .filter(s -> !s.isBlank())
                .map(s -> Arrays.stream(s.split(",")).map(String::strip).collect(java.util.stream.Collectors.toSet()))
                .orElse(Set.of());
        List<GoldenArticle> articles = new ArrayList<>();
        for (String line : jsonl.split("\n")) {
            if (line.isBlank() || articles.size() >= limit) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, String> row = MAPPER.readValue(line, Map.class);
            if (!ids.isEmpty() && !ids.contains(row.get("id"))) {
                continue;
            }
            articles.add(new GoldenArticle(row.get("id"), row.get("title"), row.get("content"), row.get("lang")));
        }
        return articles;
    }
}
