package com.esmile.axis.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Key-value app configuration persisted in DB. Used by Digest 2.0 to store
 * user-configured AI provider settings (api key, endpoint, model) so the
 * settings page UI actually takes effect on the backend, replacing the
 * previous localStorage-only flow.
 *
 * <p>API key values marked {@code encrypted=true} are AES ciphertext (see
 * {@code AiConfigService}); plaintext is never written to the DB.
 *
 * <p>Known keys: {@code ai.api_key}, {@code ai.endpoint}, {@code ai.model}.
 */
@Entity
@Table(name = "app_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppConfig {

    @Id
    @Column(length = 100)
    private String key;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String value;

    @Column(nullable = false)
    @Builder.Default
    private boolean encrypted = false;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
