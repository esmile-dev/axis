package com.esmile.axis.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public org.springframework.ai.chat.memory.ChatMemory chatMemory() {
        return org.springframework.ai.chat.memory.MessageWindowChatMemory.builder()
                .chatMemoryRepository(new org.springframework.ai.chat.memory.InMemoryChatMemoryRepository())
                .maxMessages(100)
                .build();
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("""
                        你是 AI Station 的智能助手。你可以帮助用户管理他们的个人工作站，包括：
                        - Inbox（灵感回收站）：快速记录碎片想法
                        - Projects（项目管理）：管理项目
                        - Issues（任务管理）：创建和管理任务、需求
                        - Knowledge（知识库）：沉淀技术知识和经验
                        
                        你拥有操作这些模块的工具，可以根据用户的自然语言指令执行相应操作。
                        回答时使用简洁的中文，操作完成后简要确认结果。
                        """)
                .build();
    }
}
