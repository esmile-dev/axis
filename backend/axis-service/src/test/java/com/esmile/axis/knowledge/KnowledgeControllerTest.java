package com.esmile.axis.knowledge;

import com.esmile.axis.knowledge.dto.KnowledgeItemDetailView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    @Test
    void import_missingFile_returns400() throws Exception {
        mockMvc.perform(multipart("/api/knowledge/import"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(knowledgeService);
    }

    @Test
    void import_fileOnly_delegatesWithNullDefaults() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "笔记.txt", "text/plain", "正文".getBytes(StandardCharsets.UTF_8));
        when(knowledgeService.createFromImport(any(), isNull(), isNull())).thenReturn(new KnowledgeItemDetailView(
                "id2", KnowledgeType.ARTICLE, "笔记", KnowledgeStatus.UNREAD, 0,
                ArtifactStatus.PENDING, ArtifactStatus.PENDING, Set.of(), null,
                Instant.parse("2026-08-03T00:00:00Z"), Instant.parse("2026-08-03T00:00:00Z"), "正文", List.of()));

        mockMvc.perform(multipart("/api/knowledge/import").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("笔记"))
                .andExpect(jsonPath("$.type").value("ARTICLE"));
        verify(knowledgeService).createFromImport(any(), isNull(), isNull());
    }

    @Test
    void import_explicitTypeAndTitle_passesThemThrough() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "书.pdf", "application/pdf", new byte[]{1, 2});
        when(knowledgeService.createFromImport(any(), eq(KnowledgeType.BOOK), eq("书名"))).thenReturn(
                new KnowledgeItemDetailView(
                        "id3", KnowledgeType.BOOK, "书名", KnowledgeStatus.UNREAD, 0,
                        ArtifactStatus.PENDING, ArtifactStatus.PENDING, Set.of(), null,
                        Instant.parse("2026-08-03T00:00:00Z"), Instant.parse("2026-08-03T00:00:00Z"), "PDF 文本", List.of()));

        mockMvc.perform(multipart("/api/knowledge/import")
                        .file(file)
                        .param("type", "BOOK")
                        .param("title", "书名"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("BOOK"))
                .andExpect(jsonPath("$.title").value("书名"));
        verify(knowledgeService).createFromImport(any(), eq(KnowledgeType.BOOK), eq("书名"));
    }
}
