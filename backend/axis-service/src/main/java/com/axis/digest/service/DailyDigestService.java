package com.axis.digest.service;

import com.axis.digest.classify.Classifier;
import com.axis.digest.classify.DigestCategory;
import com.axis.digest.fetch.Article;
import com.axis.digest.fetch.RssFetcherService;
import com.axis.digest.store.DigestExecutionLog;
import com.axis.digest.store.DigestExecutionLogRepository;
import com.axis.digest.store.DigestExecutionStatus;
import com.axis.entity.InboxItem;
import com.axis.enums.InboxItemStatus;
import com.axis.enums.InboxItemType;
import com.axis.repository.InboxItemRepository;
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
 *   idempotency check → parallel fetch → classify → write inbox items → log
 * </pre>
 *
 * <p>Articles are persisted as {@code inbox_item} rows ({@code type=DIGEST}),
 * surfaced in the Inbox page. Only a {@code COMPLETED} log row blocks re-runs;
 * a {@code PENDING}/{@code FAILED} row (previous crash or failure) is retried —
 * stale digest items for the day are removed first so retries stay idempotent.
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

        // 3. Fetch, classify, persist as a single aggregated inbox item per day.
        int articleCount = 0;
        try {
            List<Article> raw = fetcherService.fetchAll();
            List<Article> classified = raw.stream()
                    .map(a -> a.withCategory(classifier.classify(a)))
                    .toList();
            // Cap per section (by importance) and cap overall; section order
            // is preserved by the SECTION_LIMITS EnumMap iteration order.
            List<Article> curated = curateBySection(classified, 16);
            InboxItem item = toAggregateItem(curated, classified.size(), today);
            inboxItemRepository.save(item);

            articleCount = curated.size();
            logRow.setStatus(DigestExecutionStatus.COMPLETED);
            logRow.setArticleCount(articleCount);
            repository.save(logRow);
            return DigestResult.completed(articleCount);
        } catch (RuntimeException e) {
            log.error("Digest failed for {}: {}", today, e.toString(), e);
            logRow.setStatus(DigestExecutionStatus.FAILED);
            logRow.setArticleCount(articleCount);
            repository.save(logRow);
            return DigestResult.failed(articleCount, e.getMessage());
        }
    }

    /**
     * Group classified articles by category, take the configured cap per section
     * (in importance order), and stop at {@code totalCap}. Articles within a
     * section are sorted by {@code publishedAt DESC} (newest first); articles
     * with no {@code publishedAt} are pushed to the end.
     */
    private static List<Article> curateBySection(List<Article> classified, int totalCap) {
        Map<DigestCategory, List<Article>> byCat = classified.stream()
                .sorted(Comparator.comparing(
                        (Article a) -> a.publishedAt() == null ? Instant.MIN : a.publishedAt()
                ).reversed())
                .collect(Collectors.groupingBy(
                        Article::category,
                        () -> new EnumMap<>(DigestCategory.class),
                        Collectors.toList()
                ));

        List<Article> out = new ArrayList<>(totalCap);
        for (Map.Entry<DigestCategory, Integer> e : SECTION_LIMITS.entrySet()) {
            List<Article> bucket = byCat.getOrDefault(e.getKey(), List.of());
            int take = Math.min(e.getValue(), bucket.size());
            for (int i = 0; i < take && out.size() < totalCap; i++) {
                out.add(bucket.get(i));
            }
            if (out.size() >= totalCap) break;
        }
        return out;
    }

    /**
     * Build the single daily-aggregated inbox item: {@code content} holds the display
     * title, {@code longText} holds the curated article list as a JSON array — the
     * frontend parses and renders it as sectioned cards.
     *
     * @param articles curated, section-ordered list (capped to 16)
     * @param totalFetched raw fetched count, used in the summary line for context
     */
    private static InboxItem toAggregateItem(List<Article> articles, int totalFetched, LocalDate today) {
        String articlesJson = articles.stream()
                .map(DailyDigestService::articleToJson)
                .collect(Collectors.joining(",", "[", "]"));

        String summary = articles.isEmpty()
                ? "今日无新文章"
                : "精选 " + articles.size() + " 篇（抓取 " + totalFetched + " 篇）";

        return InboxItem.builder()
                .content("今日 AI 摘要（" + today + "）")
                .status(InboxItemStatus.TODO)
                .type(InboxItemType.DIGEST)
                .summary(summary)
                .longText(articlesJson)
                .digestDate(today)
                .build();
    }

    /** Hand-rolled JSON for one article — avoids pulling Jackson into axis-service. */
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

    private static String json(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
