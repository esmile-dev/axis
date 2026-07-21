package com.axis.digest.service;

import com.axis.digest.classify.Classifier;
import com.axis.digest.classify.DigestCategory;
import com.axis.digest.fetch.Article;
import com.axis.digest.fetch.RssFetcherService;
import com.axis.digest.store.DigestExecutionLog;
import com.axis.digest.store.DigestExecutionLogRepository;
import com.axis.digest.store.DigestExecutionStatus;
import com.axis.digest.store.DigestInboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the full daily-digest pipeline:
 * <pre>
 *   idempotency check → parallel fetch → classify → write file → log
 * </pre>
 *
 * <p>Returns a {@link DigestResult} suitable for direct JSON serialization
 * by {@code DigestController}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyDigestService {

    private static final int ITEMS_PER_CATEGORY = 8;
    private static final int MIN_ITEMS_PER_CATEGORY = 5;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final DigestExecutionLogRepository repository;
    private final RssFetcherService fetcherService;
    private final Classifier classifier;
    private final DigestInboxWriter inboxWriter;

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
     * Trigger the daily digest. Idempotent — returns {@code executed=false} if today's
     * digest already exists.
     */
    @Transactional
    public DigestResult trigger() {
        LocalDate today = LocalDate.now();
        return runOnce(today);
    }

    private DigestResult runOnce(LocalDate today) {
        // 1. Hard idempotency check: row already present?
        Optional<DigestExecutionLog> existing = repository.findByDigestDateForUpdate(today);
        if (existing.isPresent()) {
            DigestExecutionLog row = existing.get();
            log.info("Digest for {} already exists (id={}, status={}); skipping",
                    today, row.getId(), row.getStatus());
            return DigestResult.skipped(row.getArticleCount());
        }

        // 2. Insert PENDING row (commits the unique constraint claim).
        DigestExecutionLog log_row = DigestExecutionLog.builder()
                .digestDate(today)
                .status(DigestExecutionStatus.PENDING)
                .articleCount(0)
                .build();
        try {
            repository.saveAndFlush(log_row);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Another caller beat us between the SELECT and INSERT. Look up the actual count.
            log.info("Concurrent trigger for {} detected via unique constraint", today);
            int existingCount = repository.findByDigestDate(today)
                    .map(DigestExecutionLog::getArticleCount)
                    .orElse(0);
            return DigestResult.skipped(existingCount);
        }

        // 3. Fetch, classify, write.
        int articleCount = 0;
        try {
            List<Article> raw = fetcherService.fetchAll();
            List<Article> classified = raw.stream()
                    .map(a -> a.withCategory(classifier.classify(a)))
                    .toList();

            Map<DigestCategory, List<Article>> grouped = classified.stream()
                    .collect(Collectors.groupingBy(Article::category));

            String markdown = renderMarkdown(today, classified, grouped);
            inboxWriter.writeDailyFile(today, markdown);

            articleCount = classified.size();
            log_row.setStatus(DigestExecutionStatus.COMPLETED);
            log_row.setArticleCount(articleCount);
            repository.save(log_row);
            return DigestResult.completed(articleCount);
        } catch (IOException | RuntimeException e) {
            log.error("Digest failed for {}: {}", today, e.toString(), e);
            log_row.setStatus(DigestExecutionStatus.FAILED);
            log_row.setArticleCount(articleCount);
            repository.save(log_row);
            return DigestResult.failed(articleCount, e.getMessage());
        }
    }

    private static String renderMarkdown(LocalDate today, List<Article> all,
                                         Map<DigestCategory, List<Article>> grouped) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 📅 Daily Digest (").append(today.format(DATE_FMT)).append(")\n\n");
        sb.append("> Generated at ")
                .append(java.time.LocalTime.now().withNano(0).toString())
                .append(" by Daily Digest. ")
                .append(all.size())
                .append(" articles across ")
                .append(grouped.size())
                .append(" categories.\n\n");

        appendCategory(sb, "AI_前沿", DigestCategory.AI_FRONTIER, grouped);
        appendCategory(sb, "技术_产业", DigestCategory.TECH_INDUSTRY, grouped);
        appendCategory(sb, "财经_科技视角", DigestCategory.FINANCE_TECH, grouped);
        appendCategory(sb, "其他_简报", DigestCategory.OTHER, grouped);

        sb.append("\n---\n");
        return sb.toString();
    }

    private static void appendCategory(StringBuilder sb, String label, DigestCategory category,
                                       Map<DigestCategory, List<Article>> grouped) {
        List<Article> items = grouped.getOrDefault(category, List.of()).stream()
                .sorted(Comparator.comparing(Article::publishedAt).reversed())
                .limit(ITEMS_PER_CATEGORY)
                .toList();

        sb.append("### ").append(label).append(" (").append(items.size()).append(")\n\n");

        if (items.isEmpty()) {
            sb.append("_(no items)_\n\n");
            return;
        }

        int n = 1;
        for (Article a : items) {
            sb.append(n++)
                    .append(". [")
                    .append(escape(a.title()))
                    .append("](")
                    .append(a.link() == null ? "" : a.link())
                    .append(") — ")
                    .append(escape(a.summary()))
                    .append("\n");
        }
        sb.append("\n");
    }

    /** Escape characters that would break Markdown list rendering. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("[", "\\[").replace("]", "\\]")
                .replace("\n", " ").replace("\r", " ");
    }

    /** Visible-for-testing / metrics hook: minimum items guarantee (unused at present). */
    @SuppressWarnings("unused")
    public static int minItemsPerCategory() { return MIN_ITEMS_PER_CATEGORY; }
}