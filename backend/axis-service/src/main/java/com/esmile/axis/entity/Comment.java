package com.esmile.axis.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "comment")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Comment {

    @Id
    @Column(length = 30)
    private String id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "issue_id", insertable = false, updatable = false)
    private String issueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id", nullable = false)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Issue issue;

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
        long timestamp = System.currentTimeMillis();
        String random = Long.toString(Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()), 36);
        return "c" + Long.toString(timestamp, 36) + random.substring(0, Math.min(8, random.length()));
    }
}
