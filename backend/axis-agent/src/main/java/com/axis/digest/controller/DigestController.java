package com.axis.digest.controller;

import com.axis.digest.service.DailyDigestService;
import com.axis.digest.service.DailyDigestService.DigestResult;
import com.axis.digest.store.DigestExecutionLog;
import com.axis.digest.store.DigestExecutionLogRepository;
import com.axis.digest.store.DigestFileReader;
import com.axis.digest.store.DigestFileReader.DailyDigestEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoints for the Daily Digest feature.
 *
 * <ul>
 *   <li>{@code POST /trigger} — manual generation (idempotent per natural day).</li>
 *   <li>{@code GET  /latest} — today's markdown + metadata, 404 if not yet generated.</li>
 *   <li>{@code GET  /recent} — last N days, newest first; missing days are skipped.</li>
 * </ul>
 *
 * <p>Scheduled triggers live in {@link com.axis.digest.scheduler.DigestScheduler}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/digest")
@RequiredArgsConstructor
public class DigestController {

    private final DailyDigestService dailyDigestService;
    private final DigestFileReader digestFileReader;
    private final DigestExecutionLogRepository executionLogRepository;

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> trigger() {
        DigestResult result = dailyDigestService.trigger();
        log.info("Manual digest trigger: executed={}, message={}, articleCount={}",
                result.executed(), result.message(), result.articleCount());
        return ResponseEntity.ok(toBody(result));
    }

    /**
     * Returns today's digest (markdown body + metadata). 404 if today's digest
     * has not been generated yet — the frontend should fall back to triggering first.
     */
    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> latest() {
        LocalDate today = LocalDate.now();
        Optional<String> content = digestFileReader.readDailyFile(today);
        if (content.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        int articleCount = executionLogRepository.findByDigestDate(today)
                .map(DigestExecutionLog::getArticleCount)
                .orElse(0);
        return ResponseEntity.ok(toBody(today, content.get(), articleCount));
    }

    /**
     * Returns up to {@code days} most-recent digests that exist on disk, newest first.
     * Default 7 days. Days with no file are skipped silently.
     */
    @GetMapping("/recent")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "7") int days) {
        int clamped = Math.max(1, Math.min(days, 30));
        List<DailyDigestEntry> entries = digestFileReader.listRecent(clamped);
        List<Map<String, Object>> result = new ArrayList<>(entries.size());
        for (DailyDigestEntry e : entries) {
            int articleCount = executionLogRepository.findByDigestDate(e.date())
                    .map(DigestExecutionLog::getArticleCount)
                    .orElse(0);
            result.add(toBody(e.date(), e.content(), articleCount));
        }
        return result;
    }

    // ---------- helpers ----------

    private static Map<String, Object> toBody(DigestResult r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executed", r.executed());
        body.put("message", r.message());
        body.put("articleCount", r.articleCount());
        return body;
    }

    private static Map<String, Object> toBody(LocalDate date, String content, int articleCount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", date.toString());
        body.put("content", content);
        body.put("articleCount", articleCount);
        return body;
    }
}