package com.esmile.axis.config;

import com.esmile.axis.entity.AppConfig;
import com.esmile.axis.repository.AppConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Manages the AI provider configuration for the whole app — both
 * Daily Digest summarization (Digest 2.0) and the chat agent share
 * the same {@link ChatClient} instance returned by {@link #get()}.
 *
 * <p><b>Resolution order at startup</b>: DB ({@code app_config} table)
 * → env vars ({@code AI_API_KEY}/{@code AI_BASE_URL}/{@code AI_MODEL}).
 * The DB is the source of truth once written; env vars are the dev
 * fallback. {@link #reload()} swaps the active {@code ChatClient}
 * atomically so other threads never see a half-constructed client.
 *
 * <p><b>Encryption</b>: API keys are AES-256 encrypted before being
 * written to the DB. The encryption password comes from
 * {@code AXIS_ENCRYPTION_PASSWORD} and the salt from
 * {@code AXIS_ENCRYPTION_SALT} (must be hex). Losing either env var
 * makes existing encrypted keys unrecoverable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigService {

    private final AppConfigRepository repository;

    @Value("${AXIS_ENCRYPTION_PASSWORD:dev-only-do-not-use-in-prod}")
    private String encryptionPassword;

    @Value("${AXIS_ENCRYPTION_SALT:0123456789abcdef}")
    private String encryptionSalt;

    @Value("${AI_API_KEY:demo}")
    private String envApiKey;

    @Value("${AI_BASE_URL:https://api.openai.com}")
    private String envBaseUrl;

    @Value("${AI_MODEL:gpt-4o-mini}")
    private String envModel;

    private volatile ChatClient currentClient;
    private volatile ResolvedConfig currentConfig;

    /** Effective AI config — {@code source} is "db" or "env" for the settings UI. */
    public record ResolvedConfig(String apiKey, String endpoint, String model, String source) {
        public String maskedApiKey() {
            if (apiKey == null || apiKey.length() < 8) return "***";
            return apiKey.substring(0, 4) + "***" + apiKey.substring(apiKey.length() - 4);
        }
    }

    @PostConstruct
    public void load() {
        ResolvedConfig cfg = loadFromDb();
        if (cfg == null) {
            cfg = loadFromEnv();
            log.info("AI config loaded from env vars (model={}, endpoint={})", cfg.model(), cfg.endpoint());
        } else {
            log.info("AI config loaded from DB (model={}, endpoint={})", cfg.model(), cfg.endpoint());
        }
        this.currentConfig = cfg;
        this.currentClient = buildClient(cfg);
    }

    /** Rebuild the ChatClient from current DB/env config. Safe to call concurrently. */
    public synchronized void reload() {
        log.info("Reloading AI config");
        load();
    }

    /** Get the active ChatClient. Never null after startup. */
    public ChatClient get() {
        return currentClient;
    }

    /** Get the current effective config (apiKey in cleartext — internal use only). */
    public ResolvedConfig getConfig() {
        return currentConfig;
    }

    /**
     * Persist user-supplied config. Encrypts the apiKey before writing.
     * Reloads on success. Throws if encryption is misconfigured.
     */
    public synchronized void save(String apiKey, String endpoint, String model) {
        TextEncryptor enc = encryptor();
        repository.save(AppConfig.builder()
                .key("ai.api_key").value(enc.encrypt(apiKey)).encrypted(true).build());
        repository.save(AppConfig.builder()
                .key("ai.endpoint").value(endpoint).encrypted(false).build());
        repository.save(AppConfig.builder()
                .key("ai.model").value(model).encrypted(false).build());
        log.info("AI config saved to DB (model={}, endpoint={})", model, endpoint);
        reload();
    }

    /**
     * Test the current config with a trivial LLM call. Returns null on success,
     * or an error message on failure. Uses the active client (post any pending reload).
     */
    public String testConnection() {
        try {
            String reply = currentClient.prompt()
                    .user("Reply with the single word: pong")
                    .call()
                    .content();
            return reply != null && reply.toLowerCase().contains("pong")
                    ? null
                    : "Unexpected reply: " + reply;
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ---------- internals ----------

    private ResolvedConfig loadFromDb() {
        Optional<AppConfig> key = repository.findById("ai.api_key");
        if (key.isEmpty()) return null;
        try {
            String apiKey = decrypt(key.get().getValue());
            String endpoint = repository.findById("ai.endpoint")
                    .map(AppConfig::getValue)
                    .orElse(envBaseUrl);
            String model = repository.findById("ai.model")
                    .map(AppConfig::getValue)
                    .orElse(envModel);
            return new ResolvedConfig(apiKey, endpoint, model, "db");
        } catch (Exception e) {
            log.error("Failed to decrypt AI config from DB; falling back to env. Check AXIS_ENCRYPTION_PASSWORD/SALT: {}",
                    e.toString());
            return null;
        }
    }

    private ResolvedConfig loadFromEnv() {
        return new ResolvedConfig(envApiKey, envBaseUrl, envModel, "env");
    }

    private ChatClient buildClient(ResolvedConfig cfg) {
        OpenAiChatOptions chatOpts = OpenAiChatOptions.builder()
                .apiKey(cfg.apiKey())
                .baseUrl(cfg.endpoint())
                .model(cfg.model())
                .temperature(0.7)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(chatOpts)
                .build();

        return ChatClient.create(chatModel);
    }

    private TextEncryptor encryptor() {
        // delux() returns TextEncryptor (string-friendly) and is sufficient for at-rest API key storage.
        return Encryptors.delux(encryptionPassword, encryptionSalt);
    }

    private String decrypt(String ciphertext) {
        return encryptor().decrypt(ciphertext);
    }
}
