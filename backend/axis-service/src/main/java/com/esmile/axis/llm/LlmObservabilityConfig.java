package com.esmile.axis.llm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 调用日志落库线程：单线程 + 有界队列，调用量是个人级（日均百条内），
 * 队列满时由 {@link java.util.concurrent.ThreadPoolExecutor.DiscardPolicy} 丢弃
 * 并静默 —— 日志丢失可接受，阻塞主链路不可接受。
 */
@Configuration
public class LlmObservabilityConfig {

    @Bean(name = "llmCallLogExecutor", destroyMethod = "shutdown")
    public Executor llmCallLogExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("llm-call-log-");
        exec.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        exec.initialize();
        return exec;
    }
}
