package com.axis.digest.scheduler;

import com.axis.digest.service.DailyDigestService;
import com.axis.digest.service.DailyDigestService.DigestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron-driven entry point. Cron expression is externalized to {@code app.digest.cron}.
 * Default: {@code "0 0 10,12,14,20,22 * * *"} — every day at 10:00, 12:00, 14:00, 20:00, 22:00.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigestScheduler {

    private final DailyDigestService dailyDigestService;

    @Scheduled(cron = "${app.digest.cron}")
    public void runScheduledDigest() {
        log.info("Scheduled digest trigger fired");
        DigestResult result = dailyDigestService.trigger();
        log.info("Scheduled digest result: executed={}, message={}, articleCount={}",
                result.executed(), result.message(), result.articleCount());
    }
}