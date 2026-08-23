package com.esmile.axis.llm;

import com.esmile.axis.llm.AiConfigService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * LLM 调用日志的统一记录点（可观测性 L0 + L2 自定义指标）。
 *
 * <p>用法：调用方在发起 LLM 请求前 {@link #start(LlmFeature)} 拿一个
 * {@link LlmCallTracker}，流式途中 {@code captureUsage(...)} 捕获末帧 usage，
 * 结束调 {@code success()} / {@code error(e)}。
 *
 * <p>落库走 {@code llmCallLogExecutor} 异步线程，任何异常静默吞掉 ——
 * 可观测性永远不能把主链路拖垮。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmCallLogger {

    private static final int ERROR_MESSAGE_MAX = 500;

    private final LlmCallLogRepository repository;
    private final MeterRegistry meterRegistry;
    private final AiConfigService aiConfigService;

    public LlmCallTracker start(LlmFeature feature) {
        return new LlmCallTracker(this, feature);
    }

    @Async("llmCallLogExecutor")
    public void record(LlmFeature feature, LlmCallStatus status, long durationMs, Usage usage, String errorMessage) {
        try {
            String model = aiConfigService.getConfig().model();
            repository.save(LlmCallLog.builder()
                    .feature(feature)
                    .model(model)
                    .promptTokens(usage != null ? usage.getPromptTokens() : null)
                    .completionTokens(usage != null ? usage.getCompletionTokens() : null)
                    .totalTokens(usage != null ? usage.getTotalTokens() : null)
                    .durationMs(durationMs)
                    .status(status)
                    .errorMessage(truncate(errorMessage))
                    .build());
            recordMetrics(feature, model, status, durationMs, usage);
        } catch (Exception e) {
            log.warn("llm.call.log.failed feature={} reason={}", feature, e.toString());
        }
    }

    private void recordMetrics(LlmFeature feature, String model, LlmCallStatus status, long durationMs, Usage usage) {
        String[] tags = {"feature", feature.name(), "model", model, "status", status.name()};
        meterRegistry.counter("llm.calls", tags).increment();
        meterRegistry.timer("llm.call.duration", tags).record(durationMs, TimeUnit.MILLISECONDS);
        if (usage != null) {
            if (usage.getPromptTokens() != null) {
                meterRegistry.counter("llm.tokens", "feature", feature.name(), "model", model,
                        "direction", "prompt").increment(usage.getPromptTokens());
            }
            if (usage.getCompletionTokens() != null) {
                meterRegistry.counter("llm.tokens", "feature", feature.name(), "model", model,
                        "direction", "completion").increment(usage.getCompletionTokens());
            }
        }
    }

    private static String truncate(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        return errorMessage.length() <= ERROR_MESSAGE_MAX
                ? errorMessage
                : errorMessage.substring(0, ERROR_MESSAGE_MAX);
    }
}
