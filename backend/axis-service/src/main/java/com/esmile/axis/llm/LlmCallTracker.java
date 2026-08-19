package com.esmile.axis.llm;

import org.springframework.ai.chat.metadata.Usage;

/**
 * 一次 LLM 调用的跟踪句柄：持有起始时间与流式途中捕获的 usage。
 * 非线程安全 —— 生命周期绑定单次请求/单条流，不跨线程共享。
 */
public class LlmCallTracker {

    private final LlmCallLogger logger;
    private final LlmFeature feature;
    private final long startNanos;

    private Usage usage;

    LlmCallTracker(LlmCallLogger logger, LlmFeature feature) {
        this.logger = logger;
        this.feature = feature;
        this.startNanos = System.nanoTime();
    }

    /** 流式场景每帧调用：保留最后一个非 null 的 usage（OpenAI 在末帧才返回）。 */
    public void captureUsage(Usage usage) {
        if (usage != null) {
            this.usage = usage;
        }
    }

    public void success() {
        logger.record(feature, LlmCallStatus.SUCCESS, elapsedMs(), usage, null);
    }

    public void error(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        logger.record(feature, LlmCallStatus.ERROR, elapsedMs(), usage, message);
    }

    private long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
