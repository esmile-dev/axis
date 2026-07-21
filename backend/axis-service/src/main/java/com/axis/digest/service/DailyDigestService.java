package com.axis.digest.service;

import com.axis.digest.classify.Classifier;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

        // 3. Fetch, classify, persist as inbox items.
        int articleCount = 0;
        try {
            List<Article> raw = fetcherService.fetchAll();
            List<InboxItem> items = raw.stream()
                    .map(a -> a.withCategory(classifier.classify(a)))
                    .map(a -> toInboxItem(a, today))
                    .toList();
            inboxItemRepository.saveAll(items);

            articleCount = items.size();
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

    /** Map a classified article to an unread DIGEST inbox item (title goes to {@code content}). */
    private static InboxItem toInboxItem(Article a, LocalDate today) {
        return InboxItem.builder()
                .content(a.title())
                .status(InboxItemStatus.TODO)
                .type(InboxItemType.DIGEST)
                .summary(a.summary())
                .link(a.link())
                .sourceName(a.sourceName())
                .category(a.category())
                .publishedAt(a.publishedAt())
                .digestDate(today)
                .build();
    }
}
