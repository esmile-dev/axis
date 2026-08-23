package com.esmile.axis.chat;

import com.esmile.axis.llm.ChatGateway;
import com.esmile.axis.llm.ChatGateway.LlmOptions;
import com.esmile.axis.llm.LlmFeature;
import com.esmile.axis.chat.ChatConversation;
import com.esmile.axis.chat.ChatLongMemory;
import com.esmile.axis.chat.ChatMessage;
import com.esmile.axis.chat.ChatConversationRepository;
import com.esmile.axis.chat.ChatLongMemoryRepository;
import com.esmile.axis.chat.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 聊天历史与长期记忆
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private static final int TITLE_MAX = 30;

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatLongMemoryRepository longMemoryRepository;
    private final ChatGateway chatGateway;

    public List<ChatConversation> listConversations() {
        return conversationRepository.findAllByOrderByUpdatedAtDesc();
    }

    public List<ChatMessage> listMessages(String conversationId) {
        return messageRepository.findByConversationIdOrderBySeqAsc(conversationId);
    }

    @Transactional
    public void deleteConversation(String conversationId) {
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.deleteById(conversationId);
    }

    public List<ChatLongMemory> listMemories() {
        return longMemoryRepository.findTop50ByOrderByCreatedAtDesc();
    }

    public ChatLongMemory saveMemory(String content) {
        return longMemoryRepository.save(ChatLongMemory.builder().content(content).build());
    }

    public ChatLongMemory findMemoryById(String id) {
        return longMemoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("ChatLongMemory not found: " + id));
    }

    public void deleteMemory(String id) {
        longMemoryRepository.deleteById(id);
    }

    public boolean conversationExists(String conversationId) {
        return conversationRepository.existsById(conversationId);
    }

    /**
     * 手动重试去重：流式失败时 MessageChatMemoryAdvisor 已在 before 阶段把 user 消息落库，
     * 直接重发会产生双份——仅当末条 user 消息与重发内容一致时先删（不一致说明当时未落库，直接发）。
     */
    @Transactional
    public void removeLastUserMessageIfMatches(String conversationId, String content) {
        messageRepository.findTopByConversationIdAndRoleOrderBySeqDesc(conversationId, "USER")
                .filter(m -> m.getContent().equals(content))
                .ifPresent(messageRepository::delete);
    }

    /**
     * 新会话首轮结束后，异步用 LLM 生成语义标题，覆盖 deriveTitle 的截断兜底。
     * 失败静默——截断标题已在库中，体验不会比升级前差。
     */
    @Async
    @Transactional
    public void generateAndUpgradeTitle(String conversationId, String firstMessage) {
        try {
            String title = generateTitle(firstMessage);
            if (title.isBlank()) {
                return;
            }
            conversationRepository.findById(conversationId).ifPresent(c -> {
                c.setTitle(title);
                conversationRepository.save(c);
            });
        } catch (Exception e) {
            log.warn("生成会话标题失败，保留截断兜底。conversationId={} reason={}", conversationId, e.toString());
        }
    }

    /** LLM 生成标题：prompt 约束 + 代码侧清洗双保险 */
    String generateTitle(String firstMessage) {
        String raw = chatGateway.call(String.format("""
                        请为以下用户消息生成一个简短的会话标题。
                        要求：使用中文，4~10 个字，越精炼越好；只输出标题本身，
                        不要引号、不要书名号、不要换行、不要标点结尾。

                        用户消息：%s
                        """, firstMessage),
                new LlmOptions(LlmFeature.TITLE_GEN));
        return sanitizeTitle(raw);
    }

    /** 清洗 LLM 输出：取首行、去首尾引号/书名号/句号、按 code point 截断 */
    static String sanitizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String title = raw.lines().findFirst().orElse("").trim()
                .replaceAll("^[\\s\"'「『《]+|[\\s\"'」』》。.]+$", "");
        return truncate(title, TITLE_MAX);
    }

    /** 按 code point 截断，避免切在 emoji 代理对中间 */
    private static String truncate(String text, int maxCodePoints) {
        if (text.codePoints().count() <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }
}
