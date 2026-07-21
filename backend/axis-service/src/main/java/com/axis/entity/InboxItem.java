package com.axis.entity;

import com.axis.digest.classify.DigestCategory;
import com.axis.enums.InboxItemStatus;
import com.axis.enums.InboxItemType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "inbox_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InboxItem {

    @Id
    @Column(length = 30)
    private String id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InboxItemStatus status = InboxItemStatus.TODO;

    /** NOTE=手动笔记；DIGEST=Daily Digest 文章（此时 content 存文章标题） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @ColumnDefault("'NOTE'")
    @Builder.Default
    private InboxItemType type = InboxItemType.NOTE;

    /** DIGEST：一句话摘要（取自 RSS description，去 HTML 截断） */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** DIGEST：原文链接 */
    @Column(length = 512)
    private String link;

    /** DIGEST：信源名（如 36氪） */
    @Column(length = 50)
    private String sourceName;

    /** DIGEST：分类桶 */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DigestCategory category;

    /** DIGEST：文章发布时间 */
    private Instant publishedAt;

    /** null=未看；点击条目时置为当前时间（置后不再覆盖） */
    private Instant readAt;

    /** DIGEST：所属 digest 日期，失败重跑时按此清理当日旧条目 */
    private LocalDate digestDate;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

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
