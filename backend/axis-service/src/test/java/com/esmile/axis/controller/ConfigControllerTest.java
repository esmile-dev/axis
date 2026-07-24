package com.esmile.axis.controller;

import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.entity.AiConfigProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for {@link ConfigController} — no Spring context,
 * works around Spring Boot 4.0's current lack of {@code @WebMvcTest} auto-config.
 */
@ExtendWith(MockitoExtension.class)
class ConfigControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AiConfigService aiConfigService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ConfigController(aiConfigService)).build();
    }

    @Test
    void get_returnsMaskedConfig() throws Exception {
        when(aiConfigService.getConfig()).thenReturn(
                new AiConfigService.ResolvedConfig("id-1", "Default", "sk-1234567890abcdef", "https://api.openai.com", "gpt-4o", "db"));

        mockMvc.perform(get("/api/v1/config/ai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("id-1"))
                .andExpect(jsonPath("$.name").value("Default"))
                .andExpect(jsonPath("$.apiKey").value("sk-1***cdef"))
                .andExpect(jsonPath("$.endpoint").value("https://api.openai.com"))
                .andExpect(jsonPath("$.model").value("gpt-4o"))
                .andExpect(jsonPath("$.source").value("db"));
    }

    @Test
    void put_savesAndReloads() throws Exception {
        when(aiConfigService.getConfig()).thenReturn(
                new AiConfigService.ResolvedConfig("id-1", "Default", "k", "e", "m", "db"));

        mockMvc.perform(put("/api/v1/config/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-new\",\"endpoint\":\"https://x.com\",\"model\":\"gpt-4o-mini\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.id").value("id-1"));

        verify(aiConfigService).save("sk-new", "https://x.com", "gpt-4o-mini");
    }

    @Test
    void reload_triggersReload() throws Exception {
        when(aiConfigService.getConfig()).thenReturn(
                new AiConfigService.ResolvedConfig("id-1", "Default", "k", "e", "m", "db"));

        mockMvc.perform(post("/api/v1/config/ai/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.source").value("db"));

        verify(aiConfigService).reload();
    }

    @Test
    void test_success() throws Exception {
        when(aiConfigService.testConnection()).thenReturn(null);
        when(aiConfigService.getConfig()).thenReturn(
                new AiConfigService.ResolvedConfig("id-1", "Default", "k", "e", "gpt-4o", "db"));

        mockMvc.perform(post("/api/v1/config/ai/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.model").value("gpt-4o"));
    }

    @Test
    void test_failure() throws Exception {
        when(aiConfigService.testConnection()).thenReturn("Unauthorized: invalid api key");

        mockMvc.perform(post("/api/v1/config/ai/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Unauthorized: invalid api key"));
    }

    // ---------- profile endpoints ----------

    @Test
    void listProfiles_returnsAllProfiles() throws Exception {
        when(aiConfigService.listProfiles()).thenReturn(List.of(
                AiConfigProfile.builder().id("p1").name("OpenAI").apiKey("enc-1").endpoint("https://api.openai.com").model("gpt-4o").active(true).build(),
                AiConfigProfile.builder().id("p2").name("DeepSeek").apiKey("enc-2").endpoint("https://api.deepseek.com").model("deepseek-v4").active(false).build()
        ));

        mockMvc.perform(get("/api/v1/config/ai/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("p1"))
                .andExpect(jsonPath("$[0].isActive").value(true))
                .andExpect(jsonPath("$[1].name").value("DeepSeek"));
    }

    @Test
    void createProfile_returnsCreatedProfile() throws Exception {
        when(aiConfigService.createProfile("DeepSeek", "sk-new", "https://api.deepseek.com", "deepseek-v4"))
                .thenReturn(AiConfigProfile.builder()
                        .id("p2")
                        .name("DeepSeek")
                        .apiKey("enc")
                        .endpoint("https://api.deepseek.com")
                        .model("deepseek-v4")
                        .active(false)
                        .build());

        mockMvc.perform(post("/api/v1/config/ai/profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DeepSeek\",\"apiKey\":\"sk-new\",\"endpoint\":\"https://api.deepseek.com\",\"model\":\"deepseek-v4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("p2"))
                .andExpect(jsonPath("$.name").value("DeepSeek"));
    }

    @Test
    void updateProfile_returnsUpdatedProfile() throws Exception {
        when(aiConfigService.updateProfile("p2", "DeepSeek Pro", "", "https://x.com", "m"))
                .thenReturn(AiConfigProfile.builder()
                        .id("p2")
                        .name("DeepSeek Pro")
                        .apiKey("enc")
                        .endpoint("https://x.com")
                        .model("m")
                        .active(false)
                        .build());

        mockMvc.perform(put("/api/v1/config/ai/profiles/p2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DeepSeek Pro\",\"apiKey\":\"\",\"endpoint\":\"https://x.com\",\"model\":\"m\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("DeepSeek Pro"))
                .andExpect(jsonPath("$.endpoint").value("https://x.com"));
    }

    @Test
    void deleteProfile_returnsSuccess() throws Exception {
        mockMvc.perform(delete("/api/v1/config/ai/profiles/p2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(aiConfigService).deleteProfile("p2");
    }

    @Test
    void activateProfile_returnsSuccess() throws Exception {
        when(aiConfigService.activateProfile("p2")).thenReturn(
                AiConfigProfile.builder().id("p2").name("DeepSeek").apiKey("enc").endpoint("e").model("m").active(true).build());

        mockMvc.perform(post("/api/v1/config/ai/profiles/p2/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.id").value("p2"));
    }

    @Test
    void testProfile_success() throws Exception {
        when(aiConfigService.testProfile("p2")).thenReturn(null);
        when(aiConfigService.findProfile("p2")).thenReturn(Optional.of(
                AiConfigProfile.builder().id("p2").name("DeepSeek").apiKey("enc").endpoint("e").model("deepseek-v4").active(true).build()));

        mockMvc.perform(post("/api/v1/config/ai/profiles/p2/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.model").value("deepseek-v4"));
    }
}
