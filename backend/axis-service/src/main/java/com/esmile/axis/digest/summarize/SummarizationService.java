package com.esmile.axis.digest.summarize;

import com.esmile.axis.digest.classify.DigestCategory;
import com.esmile.axis.digest.fetch.Article;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummarizationService {

    private static final String PROMPT_SUMMARIZE = """
            你是技术媒体编辑。阅读以下 RSS 文章，输出严格 JSON（无 markdown 代码块、无前后缀）：

            输入：
            - title: {title}
            - source: {sourceName}
            - link: {link}
            - description: {description}

            输出 schema（所有字段必填，中文）：
            {
              "headline": "中文标题，20 字以内",
              "tldr": "一句话说清发生了什么，不超过 50 字",
              "detail": "2-3 句关键事实，含数据/人物/背景，80-150 字",
              "why_it_matters": "为什么值得开发者/技术从业者关注，20-100 字",
              "source": "{sourceName}",
              "url": "{link}"
            }
            """;

    private static final String PROMPT_EDITOR = """
            你是技术日报主编。以下是今日按版面分组的入选新闻（JSON）：

            输入：
            - sections: {
                "ai":      [{headline, tldr, why_it_matters, source, url}, ...],
                "tech":    [...],
                "finance": [...],
                "other":   [...]
              }

            输出严格 JSON：
            {
              "headline": "今日日报总标题，15-25 字",
              "opening": "2 句话开场白，串起今日主线，50-80 字",
              "sections": {
                "ai":      { "lede": "≤3 句导语", "articleOrder": ["url1","url2"] },
                "tech":    { ... },
                "finance": { ... },
                "other":   { ... }
              }
            }

            版面条目按"读者最该先看"排序，跨源讲同一事件的合并到一条。
            """;

    private static final String PROMPT_VERSION = sha256(PROMPT_SUMMARIZE);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RETRIES = 1;

    private final ChatClient chatClient;
    private final ArticleSummaryCacheRepository cacheRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Summarize one article. Returns a cached result if available; otherwise calls the LLM.
     * Any failure (timeout, bad JSON, etc.) returns a fallback summary built from the RSS
     * description so the digest pipeline can continue.
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
                String raw = callLlm(prompt);
                Map<String, Object> json = parseJson(raw);
                ArticleSummary summary = new ArticleSummary(
                        str(json, "headline"),
                        str(json, "tldr"),
                        str(json, "detail"),
                        str(json, "why_it_matters"),
                        str(json, "source", article.sourceName()),
                        str(json, "url", article.link()),
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
                prompt = prompt + "\n\n注意：必须严格输出上述 JSON schema，不要 markdown 代码块。";
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
            String raw = callLlm(prompt);
            Map<String, Object> json = parseJson(raw);
            @SuppressWarnings("unchecked")
            Map<String, Object> sections = (Map<String, Object>) json.getOrDefault("sections", Map.of());
            return new EditorOutput(
                    str(json, "headline"),
                    str(json, "opening"),
                    parseSectionLedes(sections)
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

    // ---------- internals ----------

    private String callLlm(String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder().timeout(TIMEOUT))
                .call()
                .content();
    }

    private Map<String, Object> parseJson(String raw) throws Exception {
        String cleaned = raw == null ? "" : raw.strip();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "").strip();
        }
        return objectMapper.readValue(cleaned, new TypeReference<>() {
        });
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

    private Map<DigestCategory, SectionLede> parseSectionLedes(Map<String, Object> sections) {
        Map<DigestCategory, SectionLede> result = new EnumMap<>(DigestCategory.class);
        log.debug("Parsing section ledes, keys={}", sections.keySet());
        for (DigestCategory cat : DigestCategory.values()) {
            String key = categoryKey(cat);
            Object raw = sections.get(key);
            if (raw instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sectionMap = (Map<String, Object>) rawMap;
                String lede = str(sectionMap, "lede");
                Object orderObj = sectionMap.get("articleOrder");
                List<String> order = List.of();
                if (orderObj instanceof List<?> list) {
                    order = list.stream().map(Object::toString).toList();
                }
                result.put(cat, new SectionLede(lede, order));
                log.debug("Parsed section {}: lede={}", key, lede);
            } else {
                log.debug("Section {} raw type {} not a map", key, raw == null ? "null" : raw.getClass().getSimpleName());
            }
        }
        return result;
    }

    private String str(Map<String, Object> map, String key) {
        return str(map, key, "");
    }

    private String str(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return v == null ? defaultValue : v.toString();
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
