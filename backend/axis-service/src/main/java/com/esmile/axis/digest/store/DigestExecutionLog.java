package com.esmile.axis.digest.store;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Records one digest execution per natural day.
 *
 * <p>Idempotency is enforced by the DB unique constraint on {@code digest_date}
 * (the hard guarantee) plus an application-level check in {@code DailyDigestService}.
 * Only a {@code COMPLETED} row blocks re-runs; {@code PENDING}/{@code FAILED}
 * rows are retried.
 */
@Entity
@Table(
        name = "digest_execution_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_digest_date", columnNames = "digest_date")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DigestExecutionLog {

    @Id
    @Column(length = 30)
    private String id;

    @Column(name = "digest_date", nullable = false)
    private LocalDate digestDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private DigestExecutionStatus status = DigestExecutionStatus.PENDING;

    @Column(name = "article_count", nullable = false)
    @Builder.Default
    private int articleCount = 0;

    /** Digest 2.0: actual LLM calls made (cache hits excluded). NFR-003 budget ≤ 17. */
    @Column(name = "llm_call_count", nullable = false)
    @Builder.Default
    private int llmCallCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = generateCuid();
        }
    }

    /** Mirrors the pattern from {@code InboxItem.generateCuid}. */
    private static String generateCuid() {
        long timestamp = System.currentTimeMillis();
        String random = Long.toString(Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()), 36);
        return "c" + Long.toString(timestamp, 36) + random.substring(0, Math.min(8, random.length()));
    }
}