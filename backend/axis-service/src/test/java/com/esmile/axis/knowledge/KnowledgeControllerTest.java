package com.esmile.axis.knowledge;

import com.esmile.axis.knowledge.dto.KnowledgeItemDetailView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for the fetch endpoint (same style as ConfigControllerTest):
 * Bean Validation 400s for bad URLs, and delegation to the service on the happy path.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {

    private MockMvc mockMvc;

    @Mock
    private KnowledgeService knowledgeService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(knowledgeService)).build();
    }

    @Test
    void fetch_nonHttpScheme_returns400() throws Exception {
        mockMvc.perform(post("/api/knowledge/fetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"ftp://example.com/a\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void fetch_blankUrl_returns400() throws Exception {
        mockMvc.perform(post("/api/knowledge/fetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void fetch_validUrl_delegatesToService() throws Exception {
        when(knowledgeService.createFromUrl("https://example.com/a")).thenReturn(new KnowledgeItemDetailView(
                "id1", KnowledgeType.ARTICLE, "抓取标题", KnowledgeStatus.UNREAD, 0,
                ArtifactStatus.PENDING, ArtifactStatus.PENDING, Set.of(), "https://example.com/a",
                Instant.parse("2026-08-03T00:00:00Z"), Instant.parse("2026-08-03T00:00:00Z"), "# 正文", List.of()));

        mockMvc.perform(post("/api/knowledge/fetch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("抓取标题"))
                .andExpect(jsonPath("$.sourceUrl").value("https://example.com/a"))
                .andExpect(jsonPath("$.summaryStatus").value("PENDING"));
        verify(knowledgeService).createFromUrl("https://example.com/a");
    }
}
