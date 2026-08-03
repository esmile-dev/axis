package com.esmile.axis.service;

import com.esmile.axis.config.AiConfigService;
import com.esmile.axis.entity.ChatConversation;
import com.esmile.axis.repository.ChatConversationRepository;
import com.esmile.axis.repository.ChatLongMemoryRepository;
import com.esmile.axis.repository.ChatMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistoryServiceTest {

    private final ChatConversationRepository conversationRepository = mock(ChatConversationRepository.class);
    private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
    private final ChatLongMemoryRepository longMemoryRepository = mock(ChatLongMemoryRepository.class);
    private final AiConfigService aiConfigService = mock(AiConfigService.class);

    private final ChatHistoryService service = new ChatHistoryService(
            conversationRepository, messageRepository, longMemoryRepository, aiConfigService);

    // ---------- sanitizeTitle ----------

    @Test
    void sanitizeTitle_plainText_passesThrough() {
        assertThat(ChatHistoryService.sanitizeTitle("RAG 调研笔记")).isEqualTo("RAG 调研笔记");
    }

    @Test
    void sanitizeTitle_stripsQuotesBookTitleMarksAndTrailingPeriod() {
        assertThat(ChatHistoryService.sanitizeTitle("「RAG 调研笔记」。")).isEqualTo("RAG 调研笔记");
        assertThat(ChatHistoryService.sanitizeTitle("《周报》")).isEqualTo("周报");
        assertThat(ChatHistoryService.sanitizeTitle("\"Weekly sync\"")).isEqualTo("Weekly sync");
    }

    @Test
    void sanitizeTitle_takesFirstLineOnly() {
        assertThat(ChatHistoryService.sanitizeTitle("标题\n这里是补充说明")).isEqualTo("标题");
    }

    @Test
    void sanitizeTitle_nullAndBlank_returnEmpty() {
        assertThat(ChatHistoryService.sanitizeTitle(null)).isEmpty();
        assertThat(ChatHistoryService.sanitizeTitle("   ")).isEmpty();
    }

    @Test
    void sanitizeTitle_truncatesByCodePoint_notByUtf16Unit() {
        // 31 个 code point，其中 emoji 占 2 个 UTF-16 unit——按 char 截会切烂代理对
        String emojiTitle = "😀".repeat(31);
        String result = ChatHistoryService.sanitizeTitle(emojiTitle);
        assertThat(result.codePoints().count()).isEqualTo(30);
        assertThat(result).doesNotContain("�");
    }

    // ---------- generateAndUpgradeTitle ----------

    @Test
    void generateAndUpgradeTitle_success_writesSanitizedTitle() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(aiConfigService.get()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("「RAG 调研笔记」\n");

        ChatConversation conversation = ChatConversation.builder()
                .id("c1").title("帮我调研一下 RAG 的方案…").build();
        when(conversationRepository.findById("c1")).thenReturn(Optional.of(conversation));

        service.generateAndUpgradeTitle("c1", "帮我调研一下 RAG 的方案");

        verify(conversationRepository).save(argThat(c -> "RAG 调研笔记".equals(c.getTitle())));
    }

    @Test
    void generateAndUpgradeTitle_blankLlmOutput_keepsFallback() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(aiConfigService.get()).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("  \n ");

        service.generateAndUpgradeTitle("c1", "帮我调研一下 RAG 的方案");

        verify(conversationRepository, never()).save(any());
    }

    @Test
    void generateAndUpgradeTitle_llmThrows_swallowedAndKeepsFallback() {
        when(aiConfigService.get()).thenThrow(new RuntimeException("LLM unavailable"));

        assertThatCode(() -> service.generateAndUpgradeTitle("c1", "随便一句"))
                .doesNotThrowAnyException();
        verify(conversationRepository, never()).save(any());
    }
}
