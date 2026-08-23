package com.esmile.axis.chat;

import com.esmile.axis.chat.ConversationSummaryService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /**
     * 短期记忆：按会话（conversationId）隔离的滑动窗口（最近 100 条），
     * 通过 JpaChatMemoryRepository 持久化到 chat_message 表，重启不丢。
     * 窗口大小与 ConversationSummaryService 共享常量——超窗消息由它预压缩成摘要，
     * 窗口内部裁剪实际不触发。
     */
    @Bean
    public ChatMemory chatMemory(JpaChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(ConversationSummaryService.MAX_MESSAGES)
                .build();
    }

    /**
     * ChatClient 不是本类 bean——由 llm 包的 AiConfigService 管理（DB 档案为准、可热重载），
     * 调用方注入 AiConfigService 后 .get() 获取。
     */
}
