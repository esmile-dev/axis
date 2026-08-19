package com.esmile.axis.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmUsageService#summarize} 纯函数聚合：today / 7d / 30d 窗口、
 * 按 feature 分组（30d 窗口）、成本估算（已知模型按价目表，未知模型不计入；
 * 窗口内无已知模型时 costUsd 为 null 而不是误导性的 0）。
 */
class LlmUsageServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    private final LlmUsageService service = new LlmUsageService(null);

    private static LlmCallLog logAt(Instant createdAt, LlmFeature feature, String model,
                                    Integer promptTokens, Integer completionTokens, long durationMs,
                                    LlmCallStatus status) {
        return LlmCallLog.builder()
                .createdAt(createdAt)
                .feature(feature)
                .model(model)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .durationMs(durationMs)
                .status(status)
                .build();
    }

    private static LlmCallLog ok(Instant at, LlmFeature feature, String model, int prompt, int completion) {
        return logAt(at, feature, model, prompt, completion, 1000, LlmCallStatus.SUCCESS);
    }

    @Test
    void emptyLogs_zeroWindowsNullCostEmptyFeatures() {
        LlmUsageService.UsageView view = service.summarize(List.of(), NOW);

        assertThat(view.today().calls()).isZero();
        assertThat(view.last7Days().calls()).isZero();
        assertThat(view.last30Days().calls()).isZero();
        assertThat(view.today().costUsd()).isNull();
        assertThat(view.byFeature()).isEmpty();
    }

    @Test
    void windowsCountByCreatedAt() {
        List<LlmCallLog> logs = List.of(
                ok(NOW.minus(1, ChronoUnit.HOURS), LlmFeature.AGENT_CHAT, "gpt-4o-mini", 100, 50),
                ok(NOW.minus(3, ChronoUnit.DAYS), LlmFeature.RERANK, "gpt-4o-mini", 200, 10),
                ok(NOW.minus(20, ChronoUnit.DAYS), LlmFeature.DIGEST_SUMMARY, "gpt-4o-mini", 300, 100));

        LlmUsageService.UsageView view = service.summarize(logs, NOW);

        assertThat(view.today().calls()).isEqualTo(1);
        assertThat(view.last7Days().calls()).isEqualTo(2);
        assertThat(view.last30Days().calls()).isEqualTo(3);
        assertThat(view.last30Days().promptTokens()).isEqualTo(600);
        assertThat(view.last30Days().completionTokens()).isEqualTo(160);
    }

    @Test
    void errorsAndAvgDuration_aggregated() {
        List<LlmCallLog> logs = List.of(
                ok(NOW, LlmFeature.AGENT_CHAT, "gpt-4o-mini", 100, 50),
                logAt(NOW, LlmFeature.AGENT_CHAT, "gpt-4o-mini", null, null, 3000, LlmCallStatus.ERROR));

        LlmUsageService.UsageView view = service.summarize(logs, NOW);

        assertThat(view.today().errors()).isEqualTo(1);
        assertThat(view.today().avgDurationMs()).isEqualTo(2000);
    }

    @Test
    void byFeature_groupsOver30Days_sortedByCallsDesc() {
        List<LlmCallLog> logs = List.of(
                ok(NOW, LlmFeature.AGENT_CHAT, "gpt-4o-mini", 100, 50),
                ok(NOW, LlmFeature.AGENT_CHAT, "gpt-4o-mini", 100, 50),
                ok(NOW, LlmFeature.RERANK, "gpt-4o-mini", 400, 20));

        LlmUsageService.UsageView view = service.summarize(logs, NOW);

        assertThat(view.byFeature()).extracting(LlmUsageService.FeatureUsage::feature)
                .containsExactly(LlmFeature.AGENT_CHAT, LlmFeature.RERANK);
        assertThat(view.byFeature().get(0).calls()).isEqualTo(2);
        assertThat(view.byFeature().get(1).promptTokens()).isEqualTo(400);
    }

    @Test
    void cost_knownModelPricedByTokens() {
        // gpt-4o-mini: $0.15 / 1M input, $0.60 / 1M output
        List<LlmCallLog> logs = List.of(
                ok(NOW, LlmFeature.AGENT_CHAT, "gpt-4o-mini", 1_000_000, 100_000));

        LlmUsageService.UsageView view = service.summarize(logs, NOW);

        assertThat(view.today().costUsd()).isEqualByComparingTo(new BigDecimal("0.21"));
    }

    @Test
    void cost_unknownModelSkipped_windowWithoutKnownModelIsNull() {
        List<LlmCallLog> logs = List.of(
                ok(NOW, LlmFeature.AGENT_CHAT, "some-unknown-model", 1000, 500));

        LlmUsageService.UsageView view = service.summarize(logs, NOW);

        assertThat(view.today().costUsd()).isNull();
        assertThat(view.today().calls()).isEqualTo(1);
    }

    @Test
    void cost_datedModelAlias_matchesByPrefix() {
        List<LlmCallLog> logs = List.of(
                ok(NOW, LlmFeature.AGENT_CHAT, "gpt-4o-mini-2024-07-18", 1_000_000, 0));

        LlmUsageService.UsageView view = service.summarize(logs, NOW);

        assertThat(view.today().costUsd()).isEqualByComparingTo(new BigDecimal("0.15"));
    }

    @Test
    void cost_kimiK3_pricedAtOfficialRate() {
        // kimi k3: $3.00 / 1M input, $15.00 / 1M output
        List<LlmCallLog> logs = List.of(
                ok(NOW, LlmFeature.AGENT_CHAT, "k3", 1_000_000, 100_000));

        LlmUsageService.UsageView view = service.summarize(logs, NOW);

        assertThat(view.today().costUsd()).isEqualByComparingTo(new BigDecimal("4.50"));
    }
}
