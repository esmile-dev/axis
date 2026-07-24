package com.esmile.axis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * A user-defined AI provider profile. Multiple profiles can be stored, but only
 * one is {@code isActive=true} at runtime. The active profile is used to build
 * the global {@link org.springframework.ai.chat.client.ChatClient}.
 *
 * <p>API keys are encrypted at rest by {@link com.esmile.axis.config.AiConfigService}.
 */
@Entity
@Table(name = "ai_config_profile")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConfigProfile {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 100, nullable = false)
    private String name;

    /** AES-encrypted API key. Never plaintext. */
    @Column(name = "api_key", columnDefinition = "TEXT", nullable = false)
    private String apiKey;

    @Column(length = 500, nullable = false)
    private String endpoint;

    @Column(length = 100, nullable = false)
    private String model;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
