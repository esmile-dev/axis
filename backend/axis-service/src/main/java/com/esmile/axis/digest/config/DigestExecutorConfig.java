package com.esmile.axis.digest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated executor for parallel RSS fetches.
 * Keeps the Spring scheduler thread free and bounds concurrency.
 */
@Configuration
public class DigestExecutorConfig {

    @Bean(name = "digestExecutor", destroyMethod = "shutdown")
    public Executor digestExecutor(DigestProperties props) {
        int size = Math.max(2, props.threadPoolSize());
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(size);
        exec.setMaxPoolSize(size * 2);
        exec.setQueueCapacity(16);
        exec.setThreadNamePrefix("digest-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(10);
        exec.initialize();
        return exec;
    }
}