package com.esmile.axis.digest.service;

import com.esmile.axis.digest.classify.Classifier;
import com.esmile.axis.digest.classify.DigestCategory;
import com.esmile.axis.digest.fetch.Article;
import com.esmile.axis.digest.fetch.RssFetcherService;
import com.esmile.axis.digest.summarize.ArticleSummary;
import com.esmile.axis.digest.summarize.ArticleSummaryCache;
import com.esmile.axis.digest.summarize.ArticleSummaryCacheRepository;
import com.esmile.axis.digest.summarize.SummarizationService;
import com.esmile.axis.digest.store.DigestExecutionLog;
import com.esmile.axis.digest.store.DigestExecutionLogRepository;
import com.esmile.axis.digest.store.DigestExecutionStatus;
import com.esmile.axis.entity.InboxItem;
import com.esmile.axis.enums.InboxItemStatus;
import com.esmile.axis.enums.InboxItemType;
import com.esmile.axis.repository.InboxItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Orchestrates the daily-digest pipeline:
 * <pre>
 *   idempotency check → parallel fetch → classify → LLM summarize/editor → write inbox item → log
 * </pre>
 *
 * <p>Digest 2.0 adds LLM fine-read + editor-in-chief passes over the curated articles.
 * Failures at any LLM step fall back to the keyword-classified version so the inbox
 * never ends up empty because of a provider issue.
 *
 * <p>Returns a {@link DigestResult} suitable for direct JSON serialization
 * by {@code DigestController}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyDigestService {

    /** Section caps, ordered by importance: AI > Tech > Finance > Other. */
    private static final Map<DigestCategory, Integer> SECTION_LIMITS =
            new EnumMap<>(DigestCategory.class);

    static {
        SECTION_LIMITS.put(DigestCategory.AI_FRONTIER, 5);
        SECTION_LIMITS.put(DigestCategory.TECH_INDUSTRY, 5);
        SECTION_LIMITS.put(DigestCategory.FINANCE_TECH, 3);
        SECTION_LIMITS.put(DigestCategory.OTHER, 3);
    }

    private final DigestExecutionLogRepository repository;
    private final InboxItemRepository inboxItemRepository;
    private final RssFetcherService fetcherService;
    private final Classifier classifier;
    private final SummarizationService summarizationService;
    private final ArticleSummaryCacheRepository cacheRepository;

    /** Outcome returned to API callers and the scheduler. */
    public record DigestResult(boolean executed, String message, int articleCount) {
        public static DigestResult skipped(int existingArticleCount) {
            return new DigestResult(false, "Today's digest already generated", existingArticleCount);
        }

        public static DigestResult completed(int count) {
            return new DigestResult(true, "Digest generated successfully", count);
        }

        public static DigestResult failed(int count, String reason) {
            return new DigestResult(false, "Digest generation failed: " + reason, count);
        }
    }

    /**
     * Trigger the daily digest. Idempotent per natural day — returns
     * {@code executed=false} if today's digest already completed.
     */
    @Transactional
    public DigestResult trigger() {
        return runOnce(LocalDate.now());
    }

    private DigestResult runOnce(LocalDate today) {
        // 1. Idempotency check: only COMPLETED blocks. PENDING/FAILED rows are reused for retry.
        Optional<DigestExecutionLog> existing = repository.findByDigestDate(today);
        if (existing.isPresent() && existing.get().getStatus() == DigestExecutionStatus.COMPLETED) {
            DigestExecutionLog row = existing.get();
            log.info("Digest for {} already completed (id={}); skipping", today, row.getId());
            return DigestResult.skipped(row.getArticleCount());
        }

        DigestExecutionLog logRow;
        if (existing.isPresent()) {
            logRow = existing.get();
            log.info("Retrying digest for {} (previous status={})", today, logRow.getStatus());
        } else {
            logRow = DigestExecutionLog.builder()
                    .digestDate(today)
                    .status(DigestExecutionStatus.PENDING)
                    .articleCount(0)
                    .build();
        }

        // 2. Retry cleanup + claim: drop articles left by a previous partial run,
        //    then (re)mark the day PENDING. The unique constraint is the hard guarantee.
        inboxItemRepository.deleteByTypeAndDigestDate(InboxItemType.DIGEST, today);
        logRow.setStatus(DigestExecutionStatus.PENDING);
        logRow.setArticleCount(0);
        logRow.setLlmCallCount(0);
        try {
            repository.saveAndFlush(logRow);
        } catch (DataIntegrityViolationException e) {
            // Another caller beat us between the SELECT and INSERT.
            log.info("Concurrent trigger for {} detected via unique constraint", today);
            int existingCount = repository.findByDigestDate(today)
                    .map(DigestExecutionLog::getArticleCount)
                    .orElse(0);
            return DigestResult.skipped(existingCount);
        }

        // 3. Fetch, classify, curate, summarize, editor, persist.
        int articleCount = 0;
        int llmCallCount = 0;
        boolean aiGenerated = false;
        try {
            List<Article> raw = fetcherService.fetchAll();
            List<Article> classified = raw.stream()
                    .map(a -> a.withCategory(classifier.classify(a)))
                    .toList();
            Map<DigestCategory, List<Article>> grouped = curateBySectionGrouped(classified, 16);
            List<Article> curated = flattenGrouped(grouped);

            // Summarize each article with cache-first logic.
            List<ArticleSummary> summaries = new ArrayList<>(curated.size());
            for (Article article : curated) {
                Optional<ArticleSummaryCache> cached = cacheRepository.findByLink(article.link());
                if (cached.isPresent()) {
                    summaries.add(fromCache(article, cached.get()));
                } else {
                    summaries.add(summarizationService.summarize(article));
                    llmCallCount++;
                }
            }

            // Editor-in-chief pass (1 additional LLM call on success).
            Map<DigestCategory, List<ArticleSummary>> summaryGrouped = summaries.stream()
                    .collect(Collectors.groupingBy(
                            ArticleSummary::category,
                            () -> new EnumMap<>(DigestCategory.class),
                            Collectors.toList()
                    ));
            SummarizationService.EditorOutput editorOutput = summarizationService.editor(summaryGrouped);
            if (editorOutput != null) {
                llmCallCount++;
                aiGenerated = true;
            }

            InboxItem item = toAggregateItem(curated, summaries, editorOutput, classified.size(), today, aiGenerated);
            inboxItemRepository.save(item);

            articleCount = curated.size();
            logRow.setStatus(DigestExecutionStatus.COMPLETED);
            logRow.setArticleCount(articleCount);
            logRow.setLlmCallCount(llmCallCount);
            repository.save(logRow);

            log.info("Digest completed for {}: articles={} llmCalls={} aiGenerated={}", today, articleCount, llmCallCount, aiGenerated);
            return DigestResult.completed(articleCount);
        } catch (RuntimeException e) {
            log.error("Digest failed for {}: {}", today, e.toString(), e);
            logRow.setStatus(DigestExecutionStatus.FAILED);
            logRow.setArticleCount(articleCount);
            logRow.setLlmCallCount(llmCallCount);
            repository.save(logRow);
            return DigestResult.failed(articleCount, e.getMessage());
        }
    }

    /**
     * Group classified articles by category, take the configured cap per section
     * (in importance order), and stop at {@code totalCap}. Returns the grouped map
     * preserving section order; articles within a section are sorted by
     * {@code publishedAt DESC}.
     */
    private static Map<DigestCategory, List<Article>> curateBySectionGrouped(List<Article> classified, int totalCap) {
        Map<DigestCategory, List<Article>> byCat = classified.stream()
                .sorted(Comparator.comparing(
                        (Article a) -> a.publishedAt() == null ? Instant.MIN : a.publishedAt()
                ).reversed())
                .collect(Collectors.groupingBy(
                        Article::category,
                        () -> new EnumMap<>(DigestCategory.class),
                        Collectors.toList()
                ));

        Map<DigestCategory, List<Article>> out = new EnumMap<>(DigestCategory.class);
        for (Map.Entry<DigestCategory, Integer> e : SECTION_LIMITS.entrySet()) {
            List<Article> bucket = byCat.getOrDefault(e.getKey(), List.of());
            int take = Math.min(e.getValue(), bucket.size());
            List<Article> taken = take == bucket.size() ? bucket : bucket.subList(0, take);
            out.put(e.getKey(), taken);
            if (out.values().stream().mapToInt(List::size).sum() >= totalCap) {
                break;
            }
        }
        return out;
    }

    private static List<Article> flattenGrouped(Map<DigestCategory, List<Article>> grouped) {
        List<Article> out = new ArrayList<>(16);
        for (Map.Entry<DigestCategory, Integer> e : SECTION_LIMITS.entrySet()) {
            List<Article> bucket = grouped.getOrDefault(e.getKey(), List.of());
            out.addAll(bucket);
        }
        return out;
    }

    private static ArticleSummary fromCache(Article article, ArticleSummaryCache c) {
        return new ArticleSummary(c.getHeadline(), c.getTldr(), c.getDetail(), c.getWhyItMatters(),
                article.sourceName(), article.link(), article.category());
    }

    /**
     * Build the single daily-aggregated inbox item.
     *
     * <p>If the editor pass succeeded, {@code longText} is an extended article array
     * containing LLM fields ({@code headline}, {@code tldr}, {@code detail},
     * {@code why_it_matters}) plus a machine-readable {@code aiGenerated} marker in
     * the first article object. If the editor failed, it falls back to the original
     * keyword-classified JSON array and adds a降级标记 to {@code summary}.
     */
    private static InboxItem toAggregateItem(List<Article> articles,
                                             List<ArticleSummary> summaries,
                                             SummarizationService.EditorOutput editorOutput,
                                             int totalFetched,
                                             LocalDate today,
                                             boolean aiGenerated) {
        String longText;
        String summary;
        if (aiGenerated && editorOutput != null) {
            longText = summaries.stream()
                    .map(s -> summaryToJson(s, true))
                    .collect(Collectors.joining(",", "[", "]"));
            summary = "精选 " + articles.size() + " 篇（抓取 " + totalFetched + " 篇）— AI 摘要";
        } else {
            longText = articles.stream()
                    .map(DailyDigestService::articleToJson)
                    .collect(Collectors.joining(",", "[", "]"));
            summary = "精选 " + articles.size() + " 篇（抓取 " + totalFetched + " 篇）— AI 摘要暂不可用，已降级";
        }

        return InboxItem.builder()
                .content("今日 AI 摘要（" + today + "）")
                .status(InboxItemStatus.TODO)
                .type(InboxItemType.DIGEST)
                .summary(summary)
                .longText(longText)
                .digestDate(today)
                .build();
    }

    /** Hand-rolled JSON for one article — keeps the frontend-compatible shape. */
    private static String articleToJson(Article a) {
        return "{"
                + "\"title\":" + json(a.title()) + ","
                + "\"summary\":" + json(a.summary() == null ? "" : a.summary()) + ","
                + "\"link\":" + json(a.link() == null ? "" : a.link()) + ","
                + "\"sourceName\":" + json(a.sourceName() == null ? "" : a.sourceName()) + ","
                + "\"category\":" + json(a.category().name()) + ","
                + "\"publishedAt\":" + json(a.publishedAt() == null ? "" : a.publishedAt().toString())
                + "}";
    }

    /** Extended JSON that adds LLM fields while keeping the frontend-compatible base shape. */
    private static String summaryToJson(ArticleSummary s, boolean markAiGenerated) {
        String marker = markAiGenerated ? "\"aiGenerated\":true," : "";
        return "{"
                + marker
                + "\"title\":" + json(s.headline()) + ","
                + "\"headline\":" + json(s.headline()) + ","
                + "\"tldr\":" + json(s.tldr()) + ","
                + "\"detail\":" + json(s.detail()) + ","
                + "\"why_it_matters\":" + json(s.whyItMatters()) + ","
                + "\"summary\":" + json(s.tldr()) + ","
                + "\"link\":" + json(s.url() == null ? "" : s.url()) + ","
                + "\"sourceName\":" + json(s.source() == null ? "" : s.source()) + ","
                + "\"category\":" + json(s.category().name()) + ","
                + "\"publishedAt\":" + json("")
                + "}";
    }

    private static String json(String s) {
        String safe = s == null ? "" : s;
        StringBuilder sb = new StringBuilder(safe.length() + 2);
        sb.append('"');
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
