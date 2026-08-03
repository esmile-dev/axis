package com.esmile.axis.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /**
     * 短期记忆：按会话（conversationId）隔离的滑动窗口（最近 100 条），
     * 通过 JpaChatMemoryRepository 持久化到 chat_message 表，重启不丢。
     */
    @Bean
    public ChatMemory chatMemory(JpaChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(100)
                .build();
    }

    /**
     * ChatClient is no longer a bean here — it is managed by
     * {@link com.esmile.axis.config.AiConfigService} in axis-service so that
     * the settings-page AI configuration (DB/env) is the single source of truth
     * and can be reloaded at runtime.
     *
     * <p>Controllers inject {@code AiConfigService} and call {@code .get()}.
     */
    public ChatClient chatClient(AiConfigService aiConfigService) {
        return aiConfigService.get();
    }
}
