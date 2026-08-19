package com.esmile.axis.llm;

import com.esmile.axis.config.AiConfigService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LlmCallLogger}：成功/失败调用落库字段（feature、model、token、耗时、状态）、
 * error_message 截断 500、指标（llm.calls / llm.call.duration / llm.tokens）、
 * 落库异常静默（可观测性不能拖垮主链路）。
 */
@ExtendWith(MockitoExtension.class)
class LlmCallLoggerTest {

    @Mock
    private LlmCallLogRepository repository;
    @Mock
    private AiConfigService aiConfigService;

    private SimpleMeterRegistry meterRegistry;
    private LlmCallLogger logger;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        logger = new LlmCallLogger(repository, meterRegistry, aiConfigService);
    }

    private void stubModel(String model) {
        when(aiConfigService.getConfig()).thenReturn(
                new AiConfigService.ResolvedConfig(null, "test", "key", "endpoint", model, "db"));
    }

    private LlmCallLog savedLog() {
        ArgumentCaptor<LlmCallLog> captor = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void success_savesLogWithFeatureModelTokensAndStatus() {
        stubModel("gpt-4o-mini");
        LlmCallTracker tracker = logger.start(LlmFeature.RERANK);
        tracker.captureUsage(new DefaultUsage(120, 30, 150));

        tracker.success();

        LlmCallLog log = savedLog();
        assertThat(log.getFeature()).isEqualTo(LlmFeature.RERANK);
        assertThat(log.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(log.getPromptTokens()).isEqualTo(120);
        assertThat(log.getCompletionTokens()).isEqualTo(30);
        assertThat(log.getTotalTokens()).isEqualTo(150);
        assertThat(log.getStatus()).isEqualTo(LlmCallStatus.SUCCESS);
        assertThat(log.getErrorMessage()).isNull();
        assertThat(log.getDurationMs()).isNotNegative();
    }

    @Test
    void success_incrementsMetricsWithFeatureAndModelTags() {
        stubModel("gpt-4o-mini");
        LlmCallTracker tracker = logger.start(LlmFeature.AGENT_CHAT);
        tracker.captureUsage(new DefaultUsage(100, 50, 150));

        tracker.success();

        assertThat(meterRegistry.get("llm.calls")
                .tags("feature", "AGENT_CHAT", "model", "gpt-4o-mini", "status", "SUCCESS")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("llm.tokens")
                .tags("feature", "AGENT_CHAT", "model", "gpt-4o-mini", "direction", "prompt")
                .counter().count()).isEqualTo(100.0);
        assertThat(meterRegistry.get("llm.tokens")
                .tags("feature", "AGENT_CHAT", "model", "gpt-4o-mini", "direction", "completion")
                .counter().count()).isEqualTo(50.0);
        assertThat(meterRegistry.get("llm.call.duration")
                .tags("feature", "AGENT_CHAT", "model", "gpt-4o-mini", "status", "SUCCESS")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void error_savesErrorStatusAndTruncatesMessageTo500() {
        stubModel("gpt-4o-mini");
        LlmCallTracker tracker = logger.start(LlmFeature.DIGEST_SUMMARY);

        tracker.error(new RuntimeException("x".repeat(600)));

        LlmCallLog log = savedLog();
        assertThat(log.getStatus()).isEqualTo(LlmCallStatus.ERROR);
        assertThat(log.getErrorMessage()).hasSize(500);
        assertThat(log.getPromptTokens()).isNull();
        assertThat(meterRegistry.get("llm.calls")
                .tags("feature", "DIGEST_SUMMARY", "model", "gpt-4o-mini", "status", "ERROR")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void usageMissing_savesNullTokens() {
        stubModel("gpt-4o-mini");

        logger.start(LlmFeature.TITLE_GEN).success();

        LlmCallLog log = savedLog();
        assertThat(log.getPromptTokens()).isNull();
        assertThat(log.getCompletionTokens()).isNull();
        assertThat(log.getTotalTokens()).isNull();
    }

    @Test
    void persistenceFails_isSilent() {
        stubModel("gpt-4o-mini");
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> logger.start(LlmFeature.KNOWLEDGE_QA).success())
                .doesNotThrowAnyException();
    }

    @Test
    void captureUsage_keepsLatestNonNullUsage() {
        stubModel("gpt-4o-mini");
        LlmCallTracker tracker = logger.start(LlmFeature.KNOWLEDGE_ASK);
        tracker.captureUsage(new DefaultUsage(1, 1, 2));
        Usage last = new DefaultUsage(10, 20, 30);
        tracker.captureUsage(last);
        tracker.captureUsage(null);

        tracker.success();

        assertThat(savedLog().getTotalTokens()).isEqualTo(30);
    }
}
