package com.esmile.axis.config;

import com.esmile.axis.entity.AiConfigProfile;
import com.esmile.axis.enums.AiProfileType;
import com.esmile.axis.repository.AiConfigProfileRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages AI provider profiles, typed by purpose ({@link AiProfileType}): one active
 * profile per type. The active CHAT profile builds the global {@link ChatClient}
 * (Daily Digest + chat agent); the active EMBEDDING profile builds the
 * {@link EmbeddingModel} for pgvector semantic search — the two may point at
 * different providers.
 *
 * <p><b>Fallback order per type</b>: active DB profile → env vars.
 *
 * <p>{@link #reload()} swaps the active {@code ChatClient} atomically so other
 * threads never see a half-constructed client, and publishes
 * {@link AiConfigReloadedEvent} so downstream stores (PgVectorStore) can rebuild.
 *
 * <p>API keys are AES-256 encrypted at rest. The encryption password/salt come
 * from {@code AXIS_ENCRYPTION_PASSWORD} and {@code AXIS_ENCRYPTION_SALT}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigService {

    /**
     * 向量维度固定 1536（与 vector_store 表 vector(1536) 对齐）。请求显式携带：
     * OpenAI text-embedding-3-small 默认即 1536 无副作用；智谱 embedding-3 等
     * 可变维度模型（默认 2048）必须显式指定才能匹配表结构。
     */
    private static final int EMBEDDING_DIMENSIONS = 1536;

    private final AiConfigProfileRepository profileRepository;
    private final ApplicationEventPublisher eventPublisher;
    /** 传入手动构造的 OpenAiChatModel，开启 Spring AI 内建的模型层观测（token 用量等指标）。 */
    private final io.micrometer.observation.ObservationRegistry observationRegistry;

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

    /** env 兜底链的 embedding 模型名（仅无激活 EMBEDDING 档案时生效）。 */
    @Value("${AI_EMBEDDING_MODEL:text-embedding-3-small}")
    private String envEmbeddingModel;

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

    /** Rebuild the ChatClient from the active DB profile or env config, then notify listeners. */
    public synchronized void reload() {
        log.info("Reloading AI config");
        load();
        eventPublisher.publishEvent(new AiConfigReloadedEvent());
    }

    /** Get the active ChatClient. Never null after startup. */
    public ChatClient get() {
        return currentClient;
    }

    /** Build an EmbeddingModel from the active EMBEDDING profile, or the env fallback chain. */
    public EmbeddingModel getEmbeddingModel() {
        return buildEmbeddingModel(resolveEmbeddingConfig());
    }

    /** Embedding 配置解析：激活的 EMBEDDING 档案 → env（AI_API_KEY/AI_BASE_URL + AI_EMBEDDING_MODEL）。 */
    ResolvedConfig resolveEmbeddingConfig() {
        Optional<AiConfigProfile> active = profileRepository.findByActiveTrueAndType(AiProfileType.EMBEDDING);
        if (active.isPresent()) {
            try {
                return toResolvedConfig(active.get());
            } catch (Exception e) {
                log.error("Failed to decrypt active embedding profile {}; falling back to env. Check AXIS_ENCRYPTION_PASSWORD/SALT: {}",
                        active.get().getId(), e.toString());
            }
        }
        return new ResolvedConfig(null, "env-fallback", envApiKey, envBaseUrl, envEmbeddingModel, "env");
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
    public AiConfigProfile createProfile(String name, String apiKey, String endpoint, String model, AiProfileType type) {
        boolean firstOfType = profileRepository.countByType(type) == 0;
        AiConfigProfile profile = AiConfigProfile.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .apiKey(encrypt(apiKey))
                .endpoint(endpoint)
                .model(model)
                .type(type)
                .active(firstOfType) // first profile of each type becomes active automatically
                .build();
        AiConfigProfile saved = profileRepository.save(profile);
        if (firstOfType) {
            reload();
        }
        log.info("AI profile created (id={}, name={}, type={})", saved.getId(), saved.getName(), saved.getType());
        return saved;
    }

    /** 更新档案（type 创建后不可变）。apiKey 留空表示保持原 key。 */
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
        // CHAT 兜底 env 通常不可用（demo key），保留"唯一激活不可删"保护；
        // EMBEDDING 无档案是合法状态（env 兜底 / 降级关键词检索），允许删除
        if (profile.isActive() && profile.getType() == AiProfileType.CHAT
                && profileRepository.countByType(profile.getType()) <= 1) {
            throw new IllegalStateException("Cannot delete the only active AI profile of type " + profile.getType());
        }
        profileRepository.delete(profile);
        if (profile.isActive()) {
            // pick the oldest remaining profile of the same type as active
            profileRepository.findByTypeOrderByCreatedAtAsc(profile.getType()).stream()
                    .findFirst()
                    .ifPresent(p -> {
                        p.setActive(true);
                        profileRepository.save(p);
                    });
            // 无论是否有继任档案都要 reload：有则切继任，无则回退 env 兜底
            reload();
        }
        log.info("AI profile deleted (id={})", id);
    }

    /** 激活档案：只停用同类型的其他档案，跨类型互不影响。 */
    @Transactional
    public AiConfigProfile activateProfile(String id) {
        AiConfigProfile target = profileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + id));
        profileRepository.findByActiveTrueAndType(target.getType()).ifPresent(current -> {
            current.setActive(false);
            profileRepository.save(current);
        });
        target.setActive(true);
        AiConfigProfile saved = profileRepository.save(target);
        reload();
        log.info("AI profile activated (id={}, name={}, type={})", saved.getId(), saved.getName(), saved.getType());
        return saved;
    }

    /** Test any profile with a real call of its own kind. Returns null on success. */
    public String testProfile(String id) {
        AiConfigProfile profile = profileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + id));
        ResolvedConfig cfg = toResolvedConfig(profile);
        return switch (profile.getType()) {
            case CHAT -> testClient(buildClient(cfg));
            case EMBEDDING -> testEmbedding(buildEmbeddingModel(cfg));
        };
    }

    // ---------- internals ----------

    private ResolvedConfig loadFromDb() {
        Optional<AiConfigProfile> active = profileRepository.findByActiveTrueAndType(AiProfileType.CHAT);
        if (active.isEmpty()) {
            return null;
        }
        try {
            return toResolvedConfig(active.get());
        } catch (Exception e) {
            log.error("Failed to decrypt active AI profile {}; falling back to env. Check AXIS_ENCRYPTION_PASSWORD/SALT: {}",
                    active.get().getId(), e.toString());
            return null;
        }
    }

    private ResolvedConfig loadFromEnv() {
        return new ResolvedConfig(null, "env-fallback", envApiKey, envBaseUrl, envModel, "env");
    }

    private ResolvedConfig toResolvedConfig(AiConfigProfile profile) {
        return new ResolvedConfig(profile.getId(), profile.getName(), decrypt(profile.getApiKey()),
                profile.getEndpoint(), profile.getModel(), "db");
    }

    private ChatClient buildClient(ResolvedConfig cfg) {
        // No temperature: null is omitted from the request, so each provider's
        // default applies. Kimi coding models reject any value other than 1.
        OpenAiChatOptions chatOpts = OpenAiChatOptions.builder()
                .apiKey(cfg.apiKey())
                .baseUrl(cfg.endpoint())
                .model(cfg.model())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(chatOpts)
                .observationRegistry(observationRegistry)
                .build();
        return ChatClient.create(chatModel);
    }

    private EmbeddingModel buildEmbeddingModel(ResolvedConfig cfg) {
        OpenAiEmbeddingOptions embeddingOpts = OpenAiEmbeddingOptions.builder()
                .apiKey(cfg.apiKey())
                .baseUrl(cfg.endpoint())
                .model(cfg.model())
                .dimensions(EMBEDDING_DIMENSIONS)
                .build();
        return OpenAiEmbeddingModel.builder().options(embeddingOpts).build();
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

    /** EMBEDDING 档案用真实 embed 调用验证（能暴露 provider 响应不兼容，如缺 usage 字段）。 */
    private String testEmbedding(EmbeddingModel model) {
        try {
            model.embed("ping");
            return null;
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
