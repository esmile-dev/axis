package com.esmile.axis.digest.summarize;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Caches the LLM fine-read summary result per article link, keyed by
 * {@code (link, model, prompt_version)}. The cache lets repeated digest
 * runs in the same day (cron 10/12/14/20/22) avoid redundant LLM calls —
 * only newly-published articles trigger a fresh summarize call.
 *
 * <p>Invalidation is implicit: if the prompt template changes,
 * {@code prompt_version} (SHA-256 of the prompt text) changes, so old
 * rows are simply not matched and a new summary is generated.
 */
@Entity
@Table(name = "article_summary_cache")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArticleSummaryCache {

    /** RSS link — natural PK. */
    @Id
    @Column(length = 512)
    private String link;

    @Column(name = "headline", columnDefinition = "TEXT", nullable = false)
    private String headline;

    @Column(name = "tldr", columnDefinition = "TEXT", nullable = false)
    private String tldr;

    @Column(name = "detail", columnDefinition = "TEXT", nullable = false)
    private String detail;

    @Column(name = "why_it_matters", columnDefinition = "TEXT", nullable = false)
    private String whyItMatters;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
