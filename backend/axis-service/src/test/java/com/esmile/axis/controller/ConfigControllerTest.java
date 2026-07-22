package com.esmile.axis.controller;

import com.esmile.axis.config.AiConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
                new AiConfigService.ResolvedConfig("sk-1234567890abcdef", "https://api.openai.com", "gpt-4o", "db"));

        mockMvc.perform(get("/api/v1/config/ai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value("sk-1***cdef"))
                .andExpect(jsonPath("$.endpoint").value("https://api.openai.com"))
                .andExpect(jsonPath("$.model").value("gpt-4o"))
                .andExpect(jsonPath("$.source").value("db"));
    }

    @Test
    void put_savesAndReloads() throws Exception {
        mockMvc.perform(put("/api/v1/config/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-new\",\"endpoint\":\"https://x.com\",\"model\":\"gpt-4o-mini\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(aiConfigService).save("sk-new", "https://x.com", "gpt-4o-mini");
    }

    @Test
    void reload_triggersReload() throws Exception {
        when(aiConfigService.getConfig()).thenReturn(
                new AiConfigService.ResolvedConfig("k", "e", "m", "db"));

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
                new AiConfigService.ResolvedConfig("k", "e", "gpt-4o", "db"));

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
}
