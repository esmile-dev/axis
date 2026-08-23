package com.esmile.axis.llm;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 用量聚合（可观测性 L1）：从 {@code llm_call_log} 取近 30 天明细，在内存聚合成
 * today / 近7天 / 近30天三个窗口 + 按 feature 分组。数据量为个人级，不做 SQL 聚合。
 */
@Service
@RequiredArgsConstructor
public class LlmUsageService {

    private final LlmCallLogRepository repository;

    public UsageView summary() {
        Instant now = Instant.now();
        List<LlmCallLog> logs = repository.findByCreatedAtAfterOrderByCreatedAtDesc(
                now.minus(30, ChronoUnit.DAYS));
        return summarize(logs, now);
    }

    /** 纯函数聚合，独立可测。 */
    UsageView summarize(List<LlmCallLog> logs, Instant now) {
        Instant todayStart = LocalDate.ofInstant(now, ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant d30 = now.minus(30, ChronoUnit.DAYS);
        List<LlmCallLog> in30d = logs.stream().filter(l -> !l.getCreatedAt().isBefore(d30)).toList();
        return new UsageView(
                window(in30d, todayStart),
                window(in30d, now.minus(7, ChronoUnit.DAYS)),
                window(in30d, d30),
                byFeature(in30d));
    }

    private static WindowUsage window(List<LlmCallLog> logs, Instant since) {
        List<LlmCallLog> in = logs.stream().filter(l -> !l.getCreatedAt().isBefore(since)).toList();
        long promptTokens = sumTokens(in, true);
        long completionTokens = sumTokens(in, false);
        return new WindowUsage(
                in.size(),
                in.stream().filter(l -> l.getStatus() == LlmCallStatus.ERROR).count(),
                promptTokens,
                completionTokens,
                avgDuration(in),
                totalCost(in));
    }

    private static List<FeatureUsage> byFeature(List<LlmCallLog> logs) {
        return logs.stream()
                .collect(java.util.stream.Collectors.groupingBy(LlmCallLog::getFeature))
                .entrySet().stream()
                .map(e -> new FeatureUsage(e.getKey(), e.getValue().size(),
                        sumTokens(e.getValue(), true), sumTokens(e.getValue(), false),
                        avgDuration(e.getValue()), totalCost(e.getValue())))
                .sorted(Comparator.comparingLong(FeatureUsage::calls).reversed())
                .toList();
    }

    private static long sumTokens(List<LlmCallLog> logs, boolean prompt) {
        return logs.stream()
                .map(prompt ? LlmCallLog::getPromptTokens : LlmCallLog::getCompletionTokens)
                .filter(Objects::nonNull)
                .mapToLong(Integer::longValue)
                .sum();
    }

    private static long avgDuration(List<LlmCallLog> logs) {
        return Math.round(logs.stream().mapToLong(LlmCallLog::getDurationMs).average().orElse(0));
    }

    /** 窗口成本：只累加已知刊例价的模型；窗口内无已知模型时为 null（未知≠0）。 */
    private static BigDecimal totalCost(List<LlmCallLog> logs) {
        return logs.stream()
                .map(l -> ModelPricing.cost(l.getModel(), l.getPromptTokens(), l.getCompletionTokens()))
                .flatMap(java.util.Optional::stream)
                .reduce(BigDecimal::add)
                .orElse(null);
    }

    public record UsageView(WindowUsage today, WindowUsage last7Days, WindowUsage last30Days,
                            List<FeatureUsage> byFeature) {
    }

    public record WindowUsage(long calls, long errors, long promptTokens, long completionTokens,
                              long avgDurationMs, BigDecimal costUsd) {
    }

    public record FeatureUsage(LlmFeature feature, long calls, long promptTokens, long completionTokens,
                               long avgDurationMs, BigDecimal costUsd) {
    }
}
