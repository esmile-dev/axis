package com.esmile.axis.chat;

import com.esmile.axis.llm.ChatGateway;
import com.esmile.axis.chat.ChatConversation;
import com.esmile.axis.chat.ChatMessage;
import com.esmile.axis.llm.LlmFeature;
import com.esmile.axis.chat.ChatConversationRepository;
import com.esmile.axis.chat.ChatMessageRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话滚动摘要：超窗预压缩最旧一批（合并已有摘要）→ 写回 + 删除；
 * 失败/空输出一律不删不改，行为退化为硬窗口截断。
 */
class ConversationSummaryServiceTest {

    private final ChatConversationRepository conversationRepository = mock(ChatConversationRepository.class);
    private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
    private final ChatGateway chatGateway = mock(ChatGateway.class);

    private final ConversationSummaryService service = new ConversationSummaryService(
            conversationRepository, messageRepository, chatGateway);

    private static ChatMessage msg(String conversationId, String role, String content, int seq) {
        return ChatMessage.builder().conversationId(conversationId).role(role).content(content).seq(seq).build();
    }

    private static List<ChatMessage> toList(Iterable<ChatMessage> it) {
        return StreamSupport.stream(it.spliterator(), false).toList();
    }

    /** 101 条消息的会话：超窗 1 条，触发压缩。 */
    private ChatConversation stubOverWindowConversation(String summary) {
        ChatConversation conversation = ChatConversation.builder()
                .id("c1").title("t").summary(summary).build();
        List<ChatMessage> all = IntStream.range(0, 101)
                .mapToObj(i -> msg("c1", i % 2 == 0 ? "USER" : "ASSISTANT", "消息" + i, i))
                .toList();
        when(messageRepository.countByConversationId("c1")).thenReturn(101L);
        when(conversationRepository.findById("c1")).thenReturn(Optional.of(conversation));
        when(messageRepository.findByConversationIdOrderBySeqAsc("c1")).thenReturn(all);
        return conversation;
    }

    @Test
    void compressIfNeeded_withinWindow_noop() {
        when(messageRepository.countByConversationId("c1"))
                .thenReturn((long) ConversationSummaryService.MAX_MESSAGES);

        service.compressIfNeeded("c1");

        verify(chatGateway, never()).call(anyString(), any());
        verify(messageRepository, never()).deleteAll(any());
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void compressIfNeeded_overWindow_compressesOldestBatchMergingExistingSummary() {
        stubOverWindowConversation("旧摘要：用户在做 Axis 项目");
        when(chatGateway.call(anyString(), any())).thenReturn("新摘要：用户在用 Spring AI 做 Axis");

        service.compressIfNeeded("c1");

        // prompt 含旧摘要（滚动合并）+ 最旧 30 条内容，不含窗口内的第 31 条起
        verify(chatGateway).call(argThat((String p) -> p.contains("旧摘要：用户在做 Axis 项目")
                        && p.contains("消息0") && p.contains("消息29")
                        && !p.contains("消息30") && !p.contains("消息100")),
                argThat((ChatGateway.LlmOptions o) -> o.feature() == LlmFeature.MEMORY_COMPRESS));
        verify(conversationRepository).save(argThat((ChatConversation c) ->
                "新摘要：用户在用 Spring AI 做 Axis".equals(c.getSummary())));
        verify(messageRepository).deleteAll(argThat((Iterable<ChatMessage> it) -> {
            List<ChatMessage> deleted = toList(it);
            return deleted.size() == ConversationSummaryService.COMPRESS_BATCH
                    && "消息0".equals(deleted.get(0).getContent())
                    && "消息29".equals(deleted.get(29).getContent());
        }));
    }

    @Test
    void compressIfNeeded_noExistingSummary_promptMarksNone() {
        stubOverWindowConversation(null);
        when(chatGateway.call(anyString(), any())).thenReturn("摘要");

        service.compressIfNeeded("c1");

        verify(chatGateway).call(argThat((String p) -> p.contains("（无）")), any());
    }

    @Test
    void compressIfNeeded_llmThrows_keepsMessagesAndSummary() {
        stubOverWindowConversation("旧摘要");
        when(chatGateway.call(anyString(), any())).thenThrow(new RuntimeException("LLM unavailable"));

        assertThatCode(() -> service.compressIfNeeded("c1")).doesNotThrowAnyException();
        verify(conversationRepository, never()).save(any());
        verify(messageRepository, never()).deleteAll(any());
    }

    @Test
    void compressIfNeeded_blankLlmOutput_keepsMessagesAndSummary() {
        stubOverWindowConversation("旧摘要");
        when(chatGateway.call(anyString(), any())).thenReturn("  \n ");

        service.compressIfNeeded("c1");

        verify(conversationRepository, never()).save(any());
        verify(messageRepository, never()).deleteAll(any());
    }

    @Test
    void compressIfNeeded_llmOutputOverMax_truncatesToLimit() {
        stubOverWindowConversation(null);
        when(chatGateway.call(anyString(), any())).thenReturn("长".repeat(500));

        service.compressIfNeeded("c1");

        verify(conversationRepository).save(argThat((ChatConversation c) ->
                c.getSummary().codePoints().count() == ConversationSummaryService.SUMMARY_MAX_CHARS));
    }

    @Test
    void findSummary_present_returnsIt() {
        when(conversationRepository.findById("c1")).thenReturn(Optional.of(
                ChatConversation.builder().id("c1").title("t").summary("摘要内容").build()));

        assertThat(service.findSummary("c1")).contains("摘要内容");
    }

    @Test
    void findSummary_blankOrMissing_returnsEmpty() {
        when(conversationRepository.findById("c1")).thenReturn(Optional.of(
                ChatConversation.builder().id("c1").title("t").summary("  ").build()));
        when(conversationRepository.findById("c2")).thenReturn(Optional.empty());

        assertThat(service.findSummary("c1")).isEmpty();
        assertThat(service.findSummary("c2")).isEmpty();
    }
}
