package com.esmile.axis.config;

import com.esmile.axis.entity.AiConfigProfile;
import com.esmile.axis.entity.AppConfig;
import com.esmile.axis.repository.AiConfigProfileRepository;
import com.esmile.axis.repository.AppConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiConfigService}: DB-first loading, env fallback, reload
 * swap, encryption, profile CRUD, activation, and legacy migration.
 */
@ExtendWith(MockitoExtension.class)
class AiConfigServiceTest {

    private static final String PASSWORD = "test-pwd";
    private static final String SALT = "0123456789abcdef";
    private static final String ENV_KEY = "env-key-xxx";
    private static final String ENV_ENDPOINT = "https://env.example.com";
    private static final String ENV_MODEL = "gpt-4o-mini";

    @Mock
    private AiConfigProfileRepository profileRepository;

    @Mock
    private AppConfigRepository legacyConfigRepository;

    private AiConfigService service;

    @BeforeEach
    void setUp() {
        service = new AiConfigService(profileRepository, legacyConfigRepository);
        ReflectionTestUtils.setField(service, "encryptionPassword", PASSWORD);
        ReflectionTestUtils.setField(service, "encryptionSalt", SALT);
        ReflectionTestUtils.setField(service, "envApiKey", ENV_KEY);
        ReflectionTestUtils.setField(service, "envBaseUrl", ENV_ENDPOINT);
        ReflectionTestUtils.setField(service, "envModel", ENV_MODEL);
    }

    @Test
    void load_dbHasActiveProfile_usesDb() {
        AiConfigProfile profile = activeProfile("db-key-xxx", "https://db.example.com", "gpt-4o");
        when(profileRepository.findByActiveTrue()).thenReturn(Optional.of(profile));

        service.load();

        assertThat(service.getConfig().source()).isEqualTo("db");
        assertThat(service.getConfig().apiKey()).isEqualTo("db-key-xxx");
        assertThat(service.getConfig().endpoint()).isEqualTo("https://db.example.com");
        assertThat(service.getConfig().model()).isEqualTo("gpt-4o");
        assertThat(service.get()).isNotNull();
    }

    @Test
    void load_noActiveProfileButLegacyKeys_migratesToProfile() {
        TextEncryptor enc = Encryptors.delux(PASSWORD, SALT);
        String ciphertext = enc.encrypt("legacy-key");
        when(profileRepository.findByActiveTrue()).thenReturn(Optional.empty());
        when(legacyConfigRepository.findById("ai.api_key")).thenReturn(Optional.of(
                AppConfig.builder().key("ai.api_key").value(ciphertext).encrypted(true).build()));
        when(legacyConfigRepository.findById("ai.endpoint")).thenReturn(Optional.of(
                AppConfig.builder().key("ai.endpoint").value("https://legacy.example.com").encrypted(false).build()));
        when(legacyConfigRepository.findById("ai.model")).thenReturn(Optional.of(
                AppConfig.builder().key("ai.model").value("legacy-model").encrypted(false).build()));
        when(profileRepository.save(any(AiConfigProfile.class))).thenAnswer(i -> i.getArgument(0));

        service.load();

        assertThat(service.getConfig().source()).isEqualTo("db");
        assertThat(service.getConfig().apiKey()).isEqualTo("legacy-key");
        assertThat(service.getConfig().model()).isEqualTo("legacy-model");
        verify(legacyConfigRepository).deleteById("ai.api_key");
        verify(legacyConfigRepository).deleteById("ai.endpoint");
        verify(legacyConfigRepository).deleteById("ai.model");
    }

    @Test
    void load_emptyDbAndNoLegacy_usesEnv() {
        when(profileRepository.findByActiveTrue()).thenReturn(Optional.empty());
        when(legacyConfigRepository.findById("ai.api_key")).thenReturn(Optional.empty());

        service.load();

        assertThat(service.getConfig().source()).isEqualTo("env");
        assertThat(service.getConfig().apiKey()).isEqualTo(ENV_KEY);
        assertThat(service.getConfig().endpoint()).isEqualTo(ENV_ENDPOINT);
        assertThat(service.getConfig().model()).isEqualTo(ENV_MODEL);
    }

    @Test
    void reload_afterConfigChange_returnsNewClient() {
        AiConfigProfile first = activeProfile("first", "https://first.com", "m1");
        AiConfigProfile second = activeProfile("second", "https://second.com", "m2");
        when(profileRepository.findByActiveTrue()).thenReturn(Optional.of(first));
        service.load();
        Object firstClient = service.get();

        when(profileRepository.findByActiveTrue()).thenReturn(Optional.of(second));
        service.reload();

        assertThat(service.get()).isNotSameAs(firstClient);
        assertThat(service.getConfig().apiKey()).isEqualTo("second");
        assertThat(service.getConfig().model()).isEqualTo("m2");
    }

    @Test
    void createProfile_firstProfileBecomesActive() {
        when(profileRepository.count()).thenReturn(0L);
        when(profileRepository.save(any(AiConfigProfile.class))).thenAnswer(i -> i.getArgument(0));

        AiConfigProfile created = service.createProfile("OpenAI", "sk-new", "https://api.openai.com", "gpt-4o-mini");

        assertThat(created.getName()).isEqualTo("OpenAI");
        assertThat(created.isActive()).isTrue();
        assertThat(created.getApiKey()).isNotEqualTo("sk-new"); // encrypted
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
    void deleteProfile_onlyActiveProfile_throws() {
        AiConfigProfile only = activeProfile("k", "e", "m");
        when(profileRepository.findById("p1")).thenReturn(Optional.of(only));
        when(profileRepository.count()).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteProfile("p1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot delete the only active");
    }

    @Test
    void activateProfile_switchesActiveFlag() {
        AiConfigProfile current = profile("p1", "k1", "https://first.com", "m1", true);
        AiConfigProfile next = profile("p2", "k2", "https://second.com", "m2", false);
        when(profileRepository.findById("p2")).thenReturn(Optional.of(next));
        when(profileRepository.findByActiveTrue())
                .thenReturn(Optional.of(current))
                .thenReturn(Optional.of(next));
        when(profileRepository.save(any(AiConfigProfile.class))).thenAnswer(i -> i.getArgument(0));

        service.activateProfile("p2");

        assertThat(current.isActive()).isFalse();
        assertThat(next.isActive()).isTrue();
    }

    @Test
    void save_legacyCompat_updatesActiveProfile() {
        AiConfigProfile active = activeProfile("old", "https://old.com", "old");
        active.setId("p1");
        when(profileRepository.findByActiveTrue()).thenReturn(Optional.of(active));
        when(profileRepository.findById("p1")).thenReturn(Optional.of(active));
        when(profileRepository.save(any(AiConfigProfile.class))).thenAnswer(i -> i.getArgument(0));

        service.save("plain-key", "https://saved.com", "gpt-4o-mini");

        ArgumentCaptor<AiConfigProfile> captor = ArgumentCaptor.forClass(AiConfigProfile.class);
        verify(profileRepository).save(captor.capture());
        assertThat(captor.getValue().getEndpoint()).isEqualTo("https://saved.com");
        assertThat(captor.getValue().getApiKey()).isNotEqualTo("plain-key");
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
