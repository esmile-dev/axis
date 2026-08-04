package com.esmile.axis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 长期记忆 — 跨会话生效的用户偏好/事实，由 Agent 通过 MemoryTool 主动保存，
 * 每次对话时注入 system prompt（上限 50 条）。
 */
@Entity
@Table(name = "chat_long_memory")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatLongMemory {

    @Id
    @Column(length = 30)
    private String id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

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
