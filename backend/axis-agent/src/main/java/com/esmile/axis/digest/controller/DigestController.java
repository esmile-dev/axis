package com.esmile.axis.digest.controller;

import com.esmile.axis.digest.service.DailyDigestService;
import com.esmile.axis.digest.service.DailyDigestService.DigestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints for the Daily Digest feature.
 *
 * <ul>
 *   <li>{@code POST /trigger} — manual generation (idempotent per natural day;
 *       articles are written to {@code inbox_item} as DIGEST entries).</li>
 * </ul>
 *
 * <p>Scheduled triggers live in {@link com.esmile.axis.digest.scheduler.DigestScheduler}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/digest")
@RequiredArgsConstructor
public class DigestController {

    private final DailyDigestService dailyDigestService;

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> trigger() {
        DigestResult result = dailyDigestService.trigger();
        log.info("Manual digest trigger: executed={}, message={}, articleCount={}",
                result.executed(), result.message(), result.articleCount());
        return ResponseEntity.ok(toBody(result));
    }

    private static Map<String, Object> toBody(DigestResult r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executed", r.executed());
        body.put("message", r.message());
        body.put("articleCount", r.articleCount());
        return body;
    }
}
