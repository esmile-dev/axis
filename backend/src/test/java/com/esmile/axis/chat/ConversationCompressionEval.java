package com.esmile.axis.chat;

import com.esmile.axis.llm.AiConfigService;
import com.esmile.axis.llm.ChatGateway;
import com.esmile.axis.chat.ChatConversation;
import com.esmile.axis.chat.ChatMessage;
import com.esmile.axis.llm.LlmCallLogger;
import com.esmile.axis.llm.LlmCallTracker;
import com.esmile.axis.chat.ChatConversationRepository;
import com.esmile.axis.chat.ChatMessageRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话滚动压缩评测：合成 101 条消息（最旧 30 条埋关键事实），真实 LLM 压缩后断言
 * 摘要保留关键事实（≥2/3 关键词命中）且不超过长度上限；已有摘要时验证滚动合并去重。
 *
 * <p>无真实 {@code AI_API_KEY} 时 assumeTrue 跳过，不红 CI。
 */
class ConversationCompressionEval {

    private static ChatGateway chatGateway;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getenv("AI_API_KEY");
        String baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.openai.com");
        String model = System.getenv().getOrDefault("AI_MODEL", "gpt-4o-mini");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank() && !"demo".equals(apiKey),
                "Skipping ConversationCompressionEval: set AI_API_KEY env var to a real key");

        OpenAiChatOptions opts = OpenAiChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .build();
        ChatClient chatClient = ChatClient.create(OpenAiChatModel.builder().options(opts).build());
        AiConfigService aiConfigService = mock(AiConfigService.class);
        when(aiConfigService.get()).thenReturn(chatClient);
        LlmCallLogger llmCallLogger = mock(LlmCallLogger.class);
        when(llmCallLogger.start(any())).thenReturn(mock(LlmCallTracker.class));
        chatGateway = new ChatGateway(aiConfigService, mock(ChatMemory.class), llmCallLogger);
    }

    private static ChatMessage msg(String conversationId, String role, String content, int seq) {
        return ChatMessage.builder().conversationId(conversationId).role(role).content(content).seq(seq).build();
    }

    /** 101 条消息：首条埋三个关键事实，其余为填充——超窗后只有最旧 30 条参与压缩。 */
    private static List<ChatMessage> conversation101(String conversationId) {
        List<ChatMessage> all = new ArrayList<>();
        all.add(msg(conversationId, "USER",
                "同步几个项目事实：我们的生产数据库是 PostgreSQL 18，端口 5433；下周三发布 v2.0。", 0));
        all.add(msg(conversationId, "ASSISTANT", "已记录。", 1));
        for (int i = 2; i <= 100; i++) {
            all.add(msg(conversationId, i % 2 == 0 ? "USER" : "ASSISTANT", "日常填充消息 " + i, i));
        }
        return all;
    }

    private ConversationSummaryService newService(String conversationId, String existingSummary,
                                                  ChatConversationRepository conversationRepository,
                                                  ChatMessageRepository messageRepository) {
        when(messageRepository.countByConversationId(conversationId)).thenReturn(101L);
        when(messageRepository.findByConversationIdOrderBySeqAsc(conversationId)).thenReturn(conversation101(conversationId));
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(
                ChatConversation.builder().id(conversationId).title("t").summary(existingSummary).build()));
        return new ConversationSummaryService(conversationRepository, messageRepository, chatGateway);
    }

    private static String compressAndCapture(ConversationSummaryService service,
                                             ChatConversationRepository conversationRepository, String conversationId) {
        service.compressIfNeeded(conversationId);
        ArgumentCaptor<ChatConversation> captor = ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationRepository).save(captor.capture());
        String summary = captor.getValue().getSummary();
        System.out.printf("ConversationCompressionEval[%s]: %s%n", conversationId, summary);
        return summary;
    }

    @Test
    void compression_retainsKeyFactsWithinLengthLimit() {
        ChatConversationRepository conversationRepository = mock(ChatConversationRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ConversationSummaryService service = newService("eval-basic", null, conversationRepository, messageRepository);

        String summary = compressAndCapture(service, conversationRepository, "eval-basic");

        long hits = Stream.of("PostgreSQL", "5433", "v2.0").filter(summary::contains).count();
        assertThat(hits).as("关键事实命中 ≥2/3，摘要=%s", summary).isGreaterThanOrEqualTo(2);
        assertThat(summary.codePoints().count())
                .isLessThanOrEqualTo(ConversationSummaryService.SUMMARY_MAX_CHARS);
        // 最旧 30 条压缩后被删除
        verify(messageRepository).deleteAll(argThat((Iterable<ChatMessage> it) ->
                StreamSupport.stream(it.spliterator(), false).count() == ConversationSummaryService.COMPRESS_BATCH));
    }

    @Test
    void compression_mergesWithExistingSummary() {
        ChatConversationRepository conversationRepository = mock(ChatConversationRepository.class);
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ConversationSummaryService service = newService("eval-merge", "已有摘要：用户的猫叫年糕。",
                conversationRepository, messageRepository);

        String summary = compressAndCapture(service, conversationRepository, "eval-merge");

        assertThat(summary).as("滚动合并：旧摘要事实保留，摘要=%s", summary).contains("年糕");
        assertThat(summary).as("滚动合并：新事实并入，摘要=%s", summary).contains("PostgreSQL");
        assertThat(summary.codePoints().count())
                .isLessThanOrEqualTo(ConversationSummaryService.SUMMARY_MAX_CHARS);
    }
}
