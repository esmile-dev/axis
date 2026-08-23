package com.esmile.axis.digest.summarize;

import com.esmile.axis.llm.ChatGateway;
import com.esmile.axis.llm.ChatGateway.LlmOptions;
import com.esmile.axis.llm.LlmFeature;
import com.esmile.axis.inbox.DigestCategory;
import com.esmile.axis.digest.fetch.Article;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM-based summarization for Daily Digest 2.0.
 *
 * <p>Two-step pipeline:
 * <ol>
 *   <li>{@link #summarize(Article)} — fine-read one article into a structured {@link ArticleSummary}.
 *       Results are cached by {@code article.link} (D4 decision). On any failure the method returns
 *       a fallback summary built from the RSS description.</li>
 *   <li>{@link #editor(Map)} — editor-in-chief pass over the sectioned summaries, producing a
 *       daily headline, opening, and per-section ledes + ordering. On failure returns {@code null}
 *       so the caller can fall back to the keyword-classified version.</li>
 * </ol>
 *
 * <p>LLM responses are bound to output records via Spring AI structured output
 * ({@code CallResponseSpec.entity()}): the converter injects the format instructions into
 * the prompt and cleans/parses the response (markdown fences, thinking tags), so there is
 * no hand-rolled JSON parsing here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizationService {

    private static final String PROMPT_SUMMARIZE = """
            你是技术媒体编辑。阅读以下 RSS 文章，为其生成中文摘要。

            输入：
            - title: {title}
            - source: {sourceName}
            - link: {link}
            - description: {description}

            输出字段要求（全部使用中文）：
            - headline: 中文标题，20 字以内
            - tldr: 一句话说清发生了什么，不超过 50 字
            - detail: 2-3 句关键事实，含数据/人物/背景，80-150 字
            - whyItMatters: 为什么值得开发者/技术从业者关注，20-100 字
            - source: 输入中的 source
            - url: 输入中的 link
            """;

    private static final String PROMPT_EDITOR = """
            你是技术日报主编。以下是今日按版面分组的入选新闻（JSON）：

            {sections}

            版面固定为 ai / tech / finance / other，每条新闻含 headline, tldr, whyItMatters, source, url。

            输出字段要求（全部使用中文）：
            - headline: 今日日报总标题，15-25 字
            - opening: 2 句话开场白，串起今日主线，50-80 字
            - sections: 每个版面一个对象，含 lede（不超过 3 句导语）与 articleOrder（按"读者最该先看"排序的 url 列表）

            跨源讲同一事件的合并到一条。
            """;

    private static final String PROMPT_VERSION = sha256(PROMPT_SUMMARIZE);
    private static final int MAX_RETRIES = 2;

    private final ChatGateway chatGateway;
    private final ArticleSummaryCacheRepository cacheRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Summarize one article. Returns a cached result if available; otherwise calls the LLM.
     * Any failure (timeout, unparseable output, etc.) returns a fallback summary built from
     * the RSS description so the digest pipeline can continue.
     */
    public ArticleSummary summarize(Article article) {
        Optional<ArticleSummaryCache> cached = cacheRepository.findByLink(article.link());
        if (cached.isPresent()) {
            log.debug("digest.cache.hit link={}", article.link());
            return fromCache(article, cached.get());
        }

        String prompt = PROMPT_SUMMARIZE
                .replace("{title}", nullToEmpty(article.title()))
                .replace("{sourceName}", nullToEmpty(article.sourceName()))
                .replace("{link}", nullToEmpty(article.link()))
                .replace("{description}", nullToEmpty(article.summary()));

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                LlmArticleSummary out = callLlm(prompt, LlmArticleSummary.class);
                ArticleSummary summary = new ArticleSummary(
                        nz(out.headline()),
                        nz(out.tldr()),
                        nz(out.detail()),
                        nz(out.whyItMatters()),
                        isBlank(out.source()) ? article.sourceName() : out.source(),
                        isBlank(out.url()) ? article.link() : out.url(),
                        article.category()
                );
                saveCache(article, summary);
                return summary;
            } catch (Exception e) {
                log.warn("digest.summarize.failed link={} attempt={} reason={}", article.link(), attempt, e.toString());
                if (attempt == MAX_RETRIES) {
                    break;
                }
                // Retry with an extra schema reminder.
                prompt = prompt + "\n\n注意：请严格按要求输出全部字段，不要遗漏或更改字段名。";
            }
        }
        return fallbackSummary(article);
    }

    /**
     * Editor-in-chief pass: produce the daily aggregated output. Returns {@code null} on failure
     * so the caller can fall back to the keyword-classified version.
     */
    public EditorOutput editor(Map<DigestCategory, List<ArticleSummary>> sectioned) {
        String inputJson = toEditorInputJson(sectioned);
        String prompt = PROMPT_EDITOR.replace("{sections}", inputJson);
        try {
            EditorJson out = callLlm(prompt, EditorJson.class);
            return new EditorOutput(
                    nz(out.headline()),
                    nz(out.opening()),
                    toSectionLedes(out.sections())
            );
        } catch (Exception e) {
            log.error("digest.editor.failed reason={}", e.toString(), e);
            return null;
        }
    }

    // ---------- output records ----------

    public record EditorOutput(
            String headline,
            String opening,
            Map<DigestCategory, SectionLede> sectionLedes
    ) {
    }

    public record SectionLede(String lede, List<String> articleOrder) {
    }

    /** LLM fine-read output for one article, bound via structured output. */
    public record LlmArticleSummary(
            String headline,
            String tldr,
            String detail,
            String whyItMatters,
            String source,
            String url
    ) {
    }

    /** LLM editor-pass output; sections use explicit fields to keep schema generation simple. */
    record EditorJson(String headline, String opening, EditorSections sections) {
    }

    record EditorSections(EditorSection ai, EditorSection tech, EditorSection finance, EditorSection other) {
    }

    record EditorSection(String lede, List<String> articleOrder) {
    }

    // ---------- internals ----------

    private <T> T callLlm(String prompt, Class<T> type) {
        return chatGateway.callEntity(prompt, type, new LlmOptions(Duration.ofSeconds(30), null, LlmFeature.DIGEST_SUMMARY));
    }

    private ArticleSummary fromCache(Article article, ArticleSummaryCache c) {
        return new ArticleSummary(c.getHeadline(), c.getTldr(), c.getDetail(), c.getWhyItMatters(),
                article.sourceName(), article.link(), article.category());
    }

    private void saveCache(Article article, ArticleSummary s) {
        try {
            // Prompt version is the full SHA-256 of the summarize prompt; model is not recorded here
            // because the cache key is link-only (D4 decision).
            cacheRepository.save(ArticleSummaryCache.builder()
                    .link(article.link())
                    .headline(s.headline())
                    .tldr(s.tldr())
                    .detail(s.detail())
                    .whyItMatters(s.whyItMatters())
                    .model(currentModelName())
                    .promptVersion(PROMPT_VERSION)
                    .build());
        } catch (Exception e) {
            log.warn("digest.cache.save.failed link={} reason={}", article.link(), e.toString());
        }
    }

    private ArticleSummary fallbackSummary(Article article) {
        String desc = article.summary();
        if (desc == null || desc.isBlank()) {
            desc = "（无摘要）";
        } else if (desc.length() > 240) {
            // Fallback display stays at the v1 truncation (FR-002); the full 4000-char
            // description is for the LLM prompt only.
            desc = desc.substring(0, 237) + "...";
        }
        String headline = article.title() != null && article.title().length() <= 20
                ? article.title()
                : (article.title() != null ? article.title().substring(0, Math.min(20, article.title().length())) + "..." : "未知标题");
        return new ArticleSummary(headline, desc, desc, "AI 摘要生成失败，已降级到 RSS 简介。",
                article.sourceName(), article.link(), article.category());
    }

    private String toEditorInputJson(Map<DigestCategory, List<ArticleSummary>> sectioned) {
        Map<String, List<Map<String, String>>> sections = new LinkedHashMap<>();
        for (DigestCategory cat : DigestCategory.values()) {
            List<ArticleSummary> list = sectioned.getOrDefault(cat, List.of());
            sections.put(categoryKey(cat), list.stream().map(this::toJsonItem).toList());
        }
        try {
            return objectMapper.writeValueAsString(sections);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize editor input", e);
        }
    }

    private static String categoryKey(DigestCategory cat) {
        return switch (cat) {
            case AI_FRONTIER -> "ai";
            case TECH_INDUSTRY -> "tech";
            case FINANCE_TECH -> "finance";
            case OTHER -> "other";
        };
    }

    private Map<String, String> toJsonItem(ArticleSummary s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("headline", s.headline());
        m.put("tldr", s.tldr());
        m.put("why_it_matters", s.whyItMatters());
        m.put("source", s.source());
        m.put("url", s.url());
        return m;
    }

    private Map<DigestCategory, SectionLede> toSectionLedes(EditorSections sections) {
        Map<DigestCategory, SectionLede> result = new EnumMap<>(DigestCategory.class);
        if (sections == null) {
            return result;
        }
        putSection(result, DigestCategory.AI_FRONTIER, sections.ai());
        putSection(result, DigestCategory.TECH_INDUSTRY, sections.tech());
        putSection(result, DigestCategory.FINANCE_TECH, sections.finance());
        putSection(result, DigestCategory.OTHER, sections.other());
        return result;
    }

    private static void putSection(Map<DigestCategory, SectionLede> result, DigestCategory cat, EditorSection section) {
        if (section == null) {
            return;
        }
        result.put(cat, new SectionLede(nz(section.lede()),
                section.articleOrder() == null ? List.of() : section.articleOrder()));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String currentModelName() {
        // ChatClient does not expose its model name; record a placeholder.
        // DailyDigestService will track the actual config separately if needed.
        return "unknown";
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input.hashCode() + "";
        }
    }
}
