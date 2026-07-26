package com.esmile.axis.config;

import com.esmile.axis.entity.AiConfigProfile;
import com.esmile.axis.entity.AppConfig;
import com.esmile.axis.repository.AiConfigProfileRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages multiple AI provider profiles. Only one profile is active at a time;
 * the active profile builds the global {@link ChatClient} used by both Daily
 * Digest and the chat agent.
 *
 * <p><b>Startup order</b>: active DB profile → legacy {@code app_config}
 * migration → env vars fallback. Legacy flat keys ({@code ai.api_key} /
 * {@code ai.endpoint} / {@code ai.model}) from Digest 2.0 are migrated into a
 * single active profile on first boot.
 *
 * <p>{@link #reload()} swaps the active {@code ChatClient} atomically so other
 * threads never see a half-constructed client.
 *
 * <p>API keys are AES-256 encrypted at rest. The encryption password/salt come
 * from {@code AXIS_ENCRYPTION_PASSWORD} and {@code AXIS_ENCRYPTION_SALT}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigService {

    private final AiConfigProfileRepository profileRepository;
    private final AppConfigRepository legacyConfigRepository;

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

    /** Effective AI config — {@code source} is "db" or "env". */
    public record ResolvedConfig(String id, String name, String apiKey, String endpoint, String model, String source) {
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
            log.info("AI config loaded from DB profile {} (model={}, endpoint={})", cfg.id(), cfg.model(), cfg.endpoint());
        }
        this.currentConfig = cfg;
        this.currentClient = buildClient(cfg);
    }

    /** Rebuild the ChatClient from the active DB profile or env config. */
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

    // ---------- profile management ----------

    public List<AiConfigProfile> listProfiles() {
        return profileRepository.findAllByOrderByCreatedAtAsc();
    }

    public Optional<AiConfigProfile> findProfile(String id) {
        return profileRepository.findById(id);
    }

    @Transactional
    public AiConfigProfile createProfile(String name, String apiKey, String endpoint, String model) {
        boolean first = profileRepository.count() == 0;
        AiConfigProfile profile = AiConfigProfile.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .apiKey(encrypt(apiKey))
                .endpoint(endpoint)
                .model(model)
                .active(first) // first profile becomes active automatically
                .build();
        AiConfigProfile saved = profileRepository.save(profile);
        if (first) {
            reload();
        }
        log.info("AI profile created (id={}, name={})", saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public AiConfigProfile updateProfile(String id, String name, String apiKey, String endpoint, String model) {
        AiConfigProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + id));
        profile.setName(name);
        if (apiKey != null && !apiKey.isBlank()) {
            profile.setApiKey(encrypt(apiKey));
        }
        profile.setEndpoint(endpoint);
        profile.setModel(model);
        AiConfigProfile saved = profileRepository.save(profile);
        if (profile.isActive()) {
            reload();
        }
        log.info("AI profile updated (id={}, name={})", saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public void deleteProfile(String id) {
        AiConfigProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + id));
        if (profile.isActive() && profileRepository.count() <= 1) {
            throw new IllegalStateException("Cannot delete the only active AI profile");
        }
        profileRepository.delete(profile);
        if (profile.isActive()) {
            // pick the oldest remaining profile as active
            profileRepository.findAllByOrderByCreatedAtAsc().stream()
                    .findFirst()
                    .ifPresent(p -> {
                        p.setActive(true);
                        profileRepository.save(p);
                        reload();
                    });
        }
        log.info("AI profile deleted (id={})", id);
    }

    @Transactional
    public AiConfigProfile activateProfile(String id) {
        AiConfigProfile target = profileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + id));
        profileRepository.findByActiveTrue().ifPresent(current -> {
            current.setActive(false);
            profileRepository.save(current);
        });
        target.setActive(true);
        AiConfigProfile saved = profileRepository.save(target);
        reload();
        log.info("AI profile activated (id={}, name={})", saved.getId(), saved.getName());
        return saved;
    }

    /** Test any profile by building a temporary ChatClient. Returns null on success. */
    public String testProfile(String id) {
        AiConfigProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + id));
        return testClient(buildClient(toResolvedConfig(profile, "db")));
    }

    /** Legacy compatibility: save/update the active profile (or create one if none). */
    @Transactional
    public synchronized void save(String apiKey, String endpoint, String model) {
        Optional<AiConfigProfile> active = profileRepository.findByActiveTrue();
        if (active.isPresent()) {
            updateProfile(active.get().getId(), active.get().getName(), apiKey, endpoint, model);
        } else {
            createProfile("Default", apiKey, endpoint, model);
        }
    }

    /** Legacy compatibility: test the active client. */
    public String testConnection() {
        return testClient(currentClient);
    }

    // ---------- internals ----------

    private ResolvedConfig loadFromDb() {
        Optional<AiConfigProfile> active = profileRepository.findByActiveTrue();
        if (active.isPresent()) {
            try {
                return toResolvedConfig(active.get(), "db");
            } catch (Exception e) {
                log.error("Failed to decrypt active AI profile {}; falling back to env. Check AXIS_ENCRYPTION_PASSWORD/SALT: {}",
                        active.get().getId(), e.toString());
                return null;
            }
        }
        AiConfigProfile migrated = migrateLegacyConfig();
        if (migrated != null) {
            return toResolvedConfig(migrated, "db");
        }
        return null;
    }

    private AiConfigProfile migrateLegacyConfig() {
        Optional<AppConfig> key = legacyConfigRepository.findById("ai.api_key");
        if (key.isEmpty()) return null;
        try {
            String apiKey = decrypt(key.get().getValue());
            String endpoint = legacyConfigRepository.findById("ai.endpoint")
                    .map(AppConfig::getValue)
                    .orElse(envBaseUrl);
            String model = legacyConfigRepository.findById("ai.model")
                    .map(AppConfig::getValue)
                    .orElse(envModel);
            AiConfigProfile profile = AiConfigProfile.builder()
                    .id(UUID.randomUUID().toString())
                    .name("Default")
                    .apiKey(encrypt(apiKey))
                    .endpoint(endpoint)
                    .model(model)
                    .active(true)
                    .build();
            AiConfigProfile saved = profileRepository.save(profile);
            // clean up legacy keys so migration only happens once
            legacyConfigRepository.deleteById("ai.api_key");
            legacyConfigRepository.deleteById("ai.endpoint");
            legacyConfigRepository.deleteById("ai.model");
            log.info("Migrated legacy AI config into profile {} (model={})", saved.getId(), model);
            return saved;
        } catch (Exception e) {
            log.error("Failed to migrate legacy AI config: {}", e.toString());
            return null;
        }
    }

    private ResolvedConfig loadFromEnv() {
        return new ResolvedConfig(null, "env-fallback", envApiKey, envBaseUrl, envModel, "env");
    }

    private ResolvedConfig toResolvedConfig(AiConfigProfile profile, String source) {
        return new ResolvedConfig(profile.getId(), profile.getName(), decrypt(profile.getApiKey()),
                profile.getEndpoint(), profile.getModel(), source);
    }

    private ChatClient buildClient(ResolvedConfig cfg) {
        OpenAiChatOptions chatOpts = OpenAiChatOptions.builder()
                .apiKey(cfg.apiKey())
                .baseUrl(cfg.endpoint())
                .model(cfg.model())
                .temperature(0.7)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().options(chatOpts).build();
        return ChatClient.create(chatModel);
    }

    private String testClient(ChatClient client) {
        try {
            String reply = client.prompt()
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

    private TextEncryptor encryptor() {
        return Encryptors.delux(encryptionPassword, encryptionSalt);
    }

    private String encrypt(String plaintext) {
        return encryptor().encrypt(plaintext);
    }

    private String decrypt(String ciphertext) {
        return encryptor().decrypt(ciphertext);
    }
}
