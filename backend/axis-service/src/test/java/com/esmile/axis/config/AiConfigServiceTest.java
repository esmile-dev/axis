package com.esmile.axis.config;

import com.esmile.axis.entity.AppConfig;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AiConfigService} covering the four scenarios required by
 * T-002: DB-first loading, env fallback, reload swap, and at-rest API-key encryption.
 *
 * <p>Uses plain Mockito (no Spring context) for speed; @Value fields are injected
 * via {@link ReflectionTestUtils}.
 */
@ExtendWith(MockitoExtension.class)
class AiConfigServiceTest {

    private static final String PASSWORD = "test-pwd";
    private static final String SALT = "0123456789abcdef";
    private static final String ENV_KEY = "env-key-xxx";
    private static final String ENV_ENDPOINT = "https://env.example.com";
    private static final String ENV_MODEL = "gpt-4o-mini";

    @Mock
    private AppConfigRepository repository;

    private AiConfigService service;

    @BeforeEach
    void setUp() {
        service = new AiConfigService(repository);
        ReflectionTestUtils.setField(service, "encryptionPassword", PASSWORD);
        ReflectionTestUtils.setField(service, "encryptionSalt", SALT);
        ReflectionTestUtils.setField(service, "envApiKey", ENV_KEY);
        ReflectionTestUtils.setField(service, "envBaseUrl", ENV_ENDPOINT);
        ReflectionTestUtils.setField(service, "envModel", ENV_MODEL);
    }

    @Test
    void load_dbHasConfig_usesDb() {
        TextEncryptor enc = Encryptors.delux(PASSWORD, SALT);
        String ciphertext = enc.encrypt("db-key-xxx");
        when(repository.findById("ai.api_key")).thenReturn(Optional.of(
                AppConfig.builder().key("ai.api_key").value(ciphertext).encrypted(true).build()));
        when(repository.findById("ai.endpoint")).thenReturn(Optional.of(
                AppConfig.builder().key("ai.endpoint").value("https://db.example.com").encrypted(false).build()));
        when(repository.findById("ai.model")).thenReturn(Optional.of(
                AppConfig.builder().key("ai.model").value("gpt-4o").encrypted(false).build()));

        service.load();

        assertThat(service.getConfig().source()).isEqualTo("db");
        assertThat(service.getConfig().apiKey()).isEqualTo("db-key-xxx");
        assertThat(service.getConfig().endpoint()).isEqualTo("https://db.example.com");
        assertThat(service.getConfig().model()).isEqualTo("gpt-4o");
        assertThat(service.get()).isNotNull();
    }

    @Test
    void load_emptyDb_usesEnv() {
        when(repository.findById("ai.api_key")).thenReturn(Optional.empty());

        service.load();

        assertThat(service.getConfig().source()).isEqualTo("env");
        assertThat(service.getConfig().apiKey()).isEqualTo(ENV_KEY);
        assertThat(service.getConfig().endpoint()).isEqualTo(ENV_ENDPOINT);
        assertThat(service.getConfig().model()).isEqualTo(ENV_MODEL);
    }

    @Test
    void reload_afterConfigChange_returnsNewClient() {
        when(repository.findById("ai.api_key")).thenReturn(Optional.empty());
        service.load();
        Object firstClient = service.get();

        when(repository.findById("ai.api_key")).thenReturn(Optional.of(
                AppConfig.builder().key("ai.api_key").value(Encryptors.delux(PASSWORD, SALT).encrypt("new"))
                        .encrypted(true).build()));
        when(repository.findById("ai.endpoint")).thenReturn(Optional.of(
                AppConfig.builder().key("ai.endpoint").value("https://new.example.com").encrypted(false).build()));
        when(repository.findById("ai.model")).thenReturn(Optional.of(
                AppConfig.builder().key("ai.model").value("claude-3").encrypted(false).build()));

        service.reload();

        assertThat(service.get()).isNotSameAs(firstClient);
        assertThat(service.getConfig().source()).isEqualTo("db");
        assertThat(service.getConfig().apiKey()).isEqualTo("new");
        assertThat(service.getConfig().model()).isEqualTo("claude-3");
    }

    @Test
    void save_persistsEncryptedApiKey_dbNeverSeesPlaintext() {
        when(repository.findById("ai.api_key")).thenReturn(Optional.empty());
        service.load();

        service.save("plain-key-zzz", "https://saved.example.com", "gpt-4o-mini");

        ArgumentCaptor<AppConfig> captor = ArgumentCaptor.forClass(AppConfig.class);
        verify(repository, times(3)).save(captor.capture());
        AppConfig savedKey = captor.getAllValues().stream()
                .filter(c -> "ai.api_key".equals(c.getKey()))
                .findFirst().orElseThrow();

        assertThat(savedKey.isEncrypted()).isTrue();
        assertThat(savedKey.getValue()).doesNotContain("plain-key-zzz");
        // Round-trip: encrypted value must be decryptable with the same password+salt.
        String decrypted = Encryptors.delux(PASSWORD, SALT).decrypt(savedKey.getValue());
        assertThat(decrypted).isEqualTo("plain-key-zzz");
    }

    @Test
    void maskedApiKey_hidesMiddleOfKey() {
        var cfg = new AiConfigService.ResolvedConfig("sk-1234567890abcdef", "x", "y", "db");
        assertThat(cfg.maskedApiKey()).isEqualTo("sk-1***cdef");
    }
}
