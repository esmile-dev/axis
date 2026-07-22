package com.esmile.axis.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
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
