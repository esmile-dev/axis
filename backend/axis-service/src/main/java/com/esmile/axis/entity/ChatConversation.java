package com.esmile.axis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 聊天会话 — id 由前端生成（UUID），作为 ChatMemory 的 conversationId
 */
@Entity
@Table(name = "chat_conversation")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatConversation {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false)
    private String title;

    /** 早期对话的滚动摘要（超窗消息的压缩产物），由 ConversationSummaryService 维护；null 表示从未压缩 */
    @Column(columnDefinition = "TEXT")
    private String summary;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
