package com.esmile.axis.llm;

import com.esmile.axis.llm.AiConfigProfile;
import com.esmile.axis.llm.AiProfileType;
import com.esmile.axis.llm.AiConfigProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiConfigService}: typed profiles (CHAT/EMBEDDING) with
 * per-type activation, DB-first loading, env fallback, reload + event, encryption.
 */
@ExtendWith(MockitoExtension.class)
class AiConfigServiceTest {

    private static final String PASSWORD = "test-pwd";
    private static final String SALT = "0123456789abcdef";
    private static final String ENV_KEY = "env-key-xxx";
    private static final String ENV_ENDPOINT = "https://env.example.com";
    private static final String ENV_MODEL = "gpt-4o-mini";
    private static final String ENV_EMBEDDING_MODEL = "text-embedding-3-small";

    @Mock
    private AiConfigProfileRepository profileRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AiConfigService service;

    @BeforeEach
    void setUp() {
        service = new AiConfigService(profileRepository, eventPublisher,
                io.micrometer.observation.ObservationRegistry.NOOP);
        ReflectionTestUtils.setField(service, "encryptionPassword", PASSWORD);
        ReflectionTestUtils.setField(service, "encryptionSalt", SALT);
        ReflectionTestUtils.setField(service, "envApiKey", ENV_KEY);
        ReflectionTestUtils.setField(service, "envBaseUrl", ENV_ENDPOINT);
        ReflectionTestUtils.setField(service, "envModel", ENV_MODEL);
        ReflectionTestUtils.setField(service, "envEmbeddingModel", ENV_EMBEDDING_MODEL);
    }

    @Test
    void load_dbHasActiveProfile_usesDb() {
        AiConfigProfile profile = activeProfile("db-key-xxx", "https://db.example.com", "gpt-4o");
        when(profileRepository.findByActiveTrueAndType(AiProfileType.CHAT)).thenReturn(Optional.of(profile));

        service.load();

        assertThat(service.getConfig().source()).isEqualTo("db");
        assertThat(service.getConfig().apiKey()).isEqualTo("db-key-xxx");
        assertThat(service.getConfig().endpoint()).isEqualTo("https://db.example.com");
        assertThat(service.getConfig().model()).isEqualTo("gpt-4o");
        assertThat(service.get()).isNotNull();
    }

    @Test
    void load_noActiveProfile_usesEnv() {
        when(profileRepository.findByActiveTrueAndType(AiProfileType.CHAT)).thenReturn(Optional.empty());

        service.load();

        assertThat(service.getConfig().source()).isEqualTo("env");
        assertThat(service.getConfig().apiKey()).isEqualTo(ENV_KEY);
        assertThat(service.getConfig().endpoint()).isEqualTo(ENV_ENDPOINT);
        assertThat(service.getConfig().model()).isEqualTo(ENV_MODEL);
    }

    @Test
    void load_activeProfileUndecryptable_fallsBackToEnv() {
        // ciphertext produced with different credentials (or junk) must not kill startup
        String badCiphertext = Encryptors.delux("other-pwd", SALT).encrypt("secret");
        AiConfigProfile broken = AiConfigProfile.builder()
                .id("p1").name("Broken").apiKey(badCiphertext)
                .endpoint("https://db.example.com").model("gpt-4o").active(true).build();
        when(profileRepository.findByActiveTrueAndType(AiProfileType.CHAT)).thenReturn(Optional.of(broken));

        service.load();

        assertThat(service.getConfig().source()).isEqualTo("env");
        assertThat(service.getConfig().apiKey()).isEqualTo(ENV_KEY);
    }

    @Test
    void reload_afterConfigChange_returnsNewClient() {
        AiConfigProfile first = activeProfile("first", "https://first.com", "m1");
        AiConfigProfile second = activeProfile("second", "https://second.com", "m2");
        when(profileRepository.findByActiveTrueAndType(AiProfileType.CHAT)).thenReturn(Optional.of(first));
        service.load();
        Object firstClient = service.get();

        when(profileRepository.findByActiveTrueAndType(AiProfileType.CHAT)).thenReturn(Optional.of(second));
        service.reload();

        assertThat(service.get()).isNotSameAs(firstClient);
        assertThat(service.getConfig().apiKey()).isEqualTo("second");
        assertThat(service.getConfig().model()).isEqualTo("m2");
    }

    @Test
    void reload_publishesReloadedEvent() {
        service.reload();

        verify(eventPublisher).publishEvent(any(AiConfigReloadedEvent.class));
    }

    @Test
    void resolveEmbeddingConfig_activeEmbeddingProfile_usesDb() {
        AiConfigProfile emb = AiConfigProfile.builder()
                .id("e1").name("Zhipu").apiKey(Encryptors.delux(PASSWORD, SALT).encrypt("emb-key"))
                .endpoint("https://open.bigmodel.cn/api/paas/v4").model("embedding-3")
                .type(AiProfileType.EMBEDDING).active(true).build();
        when(profileRepository.findByActiveTrueAndType(AiProfileType.EMBEDDING)).thenReturn(Optional.of(emb));

        AiConfigService.ResolvedConfig cfg = service.resolveEmbeddingConfig();

        assertThat(cfg.source()).isEqualTo("db");
        assertThat(cfg.apiKey()).isEqualTo("emb-key");
        assertThat(cfg.endpoint()).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(cfg.model()).isEqualTo("embedding-3");
    }

    @Test
    void resolveEmbeddingConfig_noEmbeddingProfile_fallsBackToEnv() {
        when(profileRepository.findByActiveTrueAndType(AiProfileType.EMBEDDING)).thenReturn(Optional.empty());

        AiConfigService.ResolvedConfig cfg = service.resolveEmbeddingConfig();

        assertThat(cfg.source()).isEqualTo("env");
        assertThat(cfg.apiKey()).isEqualTo(ENV_KEY);
        assertThat(cfg.endpoint()).isEqualTo(ENV_ENDPOINT);
        assertThat(cfg.model()).isEqualTo(ENV_EMBEDDING_MODEL);
    }

    @Test
    void createProfile_firstOfTypeBecomesActive() {
        when(profileRepository.countByType(AiProfileType.CHAT)).thenReturn(0L);
        when(profileRepository.save(any(AiConfigProfile.class))).thenAnswer(i -> i.getArgument(0));

        AiConfigProfile created = service.createProfile("OpenAI", "sk-new", "https://api.openai.com", "gpt-4o-mini", AiProfileType.CHAT);

        assertThat(created.getName()).isEqualTo("OpenAI");
        assertThat(created.getType()).isEqualTo(AiProfileType.CHAT);
        assertThat(created.isActive()).isTrue();
        assertThat(created.getApiKey()).isNotEqualTo("sk-new"); // encrypted
    }

    @Test
    void createProfile_secondOfSameType_notActive() {
        when(profileRepository.countByType(AiProfileType.EMBEDDING)).thenReturn(1L);
        when(profileRepository.save(any(AiConfigProfile.class))).thenAnswer(i -> i.getArgument(0));

        AiConfigProfile created = service.createProfile("Zhipu", "sk-z", "https://open.bigmodel.cn/api/paas/v4", "embedding-3", AiProfileType.EMBEDDING);

        assertThat(created.isActive()).isFalse();
    }

    @Test
    void updateProfile_blankApiKey_keepsOldKey() {
        AiConfigProfile existing = activeProfile("old-key", "https://old.com", "old-model");
        when(profileRepository.findById("p1")).thenReturn(Optional.of(existing));
        when(profileRepository.save(any(AiConfigProfile.class))).thenAnswer(i -> i.getArgument(0));

        AiConfigProfile updated = service.updateProfile("p1", "New Name", "", "https://new.com", "new-model");

        assertThat(updated.getName()).isEqualTo("New Name");
        assertThat(updated.getApiKey()).isEqualTo(existing.getApiKey());
        assertThat(updated.getEndpoint()).isEqualTo("https://new.com");
    }

    @Test
    void deleteProfile_onlyActiveProfileOfType_throws() {
        AiConfigProfile only = activeProfile("k", "e", "m");
        when(profileRepository.findById("p1")).thenReturn(Optional.of(only));
        when(profileRepository.countByType(AiProfileType.CHAT)).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteProfile("p1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot delete the only active");
    }

    @Test
    void deleteProfile_onlyActiveEmbeddingProfile_allowedAndFallsBackToEnv() {
        // EMBEDDING 无档案是合法状态（env 兜底），允许删除唯一激活档案
        AiConfigProfile only = AiConfigProfile.builder()
                .id("e1").name("Zhipu").apiKey("enc").endpoint("https://open.bigmodel.cn/api/paas/v4")
                .model("embedding-3").type(AiProfileType.EMBEDDING).active(true).build();
        when(profileRepository.findById("e1")).thenReturn(Optional.of(only));

        service.deleteProfile("e1");

        verify(profileRepository).delete(only);
        verify(eventPublisher).publishEvent(any(AiConfigReloadedEvent.class));
    }

    @Test
    void activateProfile_switchesActiveFlagWithinType() {
        AiConfigProfile current = profile("p1", "k1", "https://first.com", "m1", true);
        AiConfigProfile next = profile("p2", "k2", "https://second.com", "m2", false);
        when(profileRepository.findById("p2")).thenReturn(Optional.of(next));
        when(profileRepository.findByActiveTrueAndType(AiProfileType.CHAT))
                .thenReturn(Optional.of(current))
                .thenReturn(Optional.of(next));
        when(profileRepository.save(any(AiConfigProfile.class))).thenAnswer(i -> i.getArgument(0));

        service.activateProfile("p2");

        assertThat(current.isActive()).isFalse();
        assertThat(next.isActive()).isTrue();
    }

    @Test
    void activateProfile_otherTypeNotAffected() {
        // CHAT 已有激活档案；激活一个 EMBEDDING 档案不应触碰 CHAT 的激活态
        AiConfigProfile chat = profile("p1", "k1", "https://first.com", "m1", true);
        AiConfigProfile emb = AiConfigProfile.builder()
                .id("e1").name("Zhipu").apiKey(Encryptors.delux(PASSWORD, SALT).encrypt("k"))
                .endpoint("https://open.bigmodel.cn/api/paas/v4").model("embedding-3")
                .type(AiProfileType.EMBEDDING).active(false).build();
        when(profileRepository.findById("e1")).thenReturn(Optional.of(emb));
        when(profileRepository.findByActiveTrueAndType(AiProfileType.EMBEDDING)).thenReturn(Optional.empty());
        when(profileRepository.findByActiveTrueAndType(AiProfileType.CHAT)).thenReturn(Optional.of(chat)); // reload 读取
        when(profileRepository.save(any(AiConfigProfile.class))).thenAnswer(i -> i.getArgument(0));

        service.activateProfile("e1");

        assertThat(emb.isActive()).isTrue();
        assertThat(chat.isActive()).isTrue();
    }

    @Test
    void maskedApiKey_hidesMiddleOfKey() {
        var cfg = new AiConfigService.ResolvedConfig("id", "name", "sk-1234567890abcdef", "x", "y", "db");
        assertThat(cfg.maskedApiKey()).isEqualTo("sk-1***cdef");
    }

    private AiConfigProfile activeProfile(String apiKey, String endpoint, String model) {
        return profile("p1", apiKey, endpoint, model, true);
    }

    private AiConfigProfile profile(String id, String apiKey, String endpoint, String model, boolean active) {
        TextEncryptor enc = Encryptors.delux(PASSWORD, SALT);
        return AiConfigProfile.builder()
                .id(id)
                .name("Test")
                .apiKey(enc.encrypt(apiKey))
                .endpoint(endpoint)
                .model(model)
                .active(active)
                .build();
    }
}
