package com.esmile.axis.chat;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 聊天消息 — 同时充当 ChatMemory 的短期记忆存储与前端历史展示。
 * 窗口裁剪由 MessageWindowChatMemory 负责（saveAll 全量替换），
 * 因此仅保留最近 maxMessages 条，更早的消息会被覆盖删除。
 */
@Entity
@Table(name = "chat_message", indexes = @Index(name = "idx_chat_message_conversation", columnList = "conversationId"))
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatMessage {

    @Id
    @Column(length = 30)
    private String id;

    @Column(nullable = false, length = 40)
    private String conversationId;

    /** USER / ASSISTANT（与 Spring AI MessageType 对应） */
    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 会话内序号，保证回放顺序 */
    @Column(nullable = false)
    private int seq;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = generateCuid();
        }
    }

    private static String generateCuid() {
        // Simple CUID-like ID generation compatible with Prisma's cuid()
        long timestamp = System.currentTimeMillis();
        String random = Long.toString(Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()), 36);
        return "c" + Long.toString(timestamp, 36) + random.substring(0, Math.min(8, random.length()));
    }
}
