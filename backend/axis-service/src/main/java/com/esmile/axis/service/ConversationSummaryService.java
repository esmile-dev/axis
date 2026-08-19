package com.esmile.axis.service;

import com.esmile.axis.config.ChatGateway;
import com.esmile.axis.config.ChatGateway.LlmOptions;
import com.esmile.axis.entity.ChatConversation;
import com.esmile.axis.entity.ChatMessage;
import com.esmile.axis.llm.LlmFeature;
import com.esmile.axis.repository.ChatConversationRepository;
import com.esmile.axis.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 会话上下文压缩 — 滚动摘要替代硬窗口截断。
 *
 * <p>预压缩策略：对话请求开始前检查，消息数超过 {@link #MAX_MESSAGES} 时把最旧
 * {@link #COMPRESS_BATCH} 条压缩进会话摘要（与已有摘要滚动合并）并删除原文。窗口因此始终
 * 留有 {@code COMPRESS_BATCH} 条余量，MessageWindowChatMemory 的内部裁剪实际不会触发。
 * 压缩失败静默降级——不删消息、不改摘要，行为退化为硬窗口截断（fail-safe）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSummaryService {

    /** 短期记忆窗口大小——AiConfig 的 MessageWindowChatMemory 引用同一常量，单一事实源 */
    public static final int MAX_MESSAGES = 100;

    /** 每次压缩的最旧消息批大小——即压缩后窗口留出的余量 */
    static final int COMPRESS_BATCH = 30;

    /** 摘要长度上限（prompt 约束 + 代码截断双保险） */
    static final int SUMMARY_MAX_CHARS = 400;

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatGateway chatGateway;

    /**
     * 超窗时压缩最旧一批消息进会话摘要。压缩的 LLM 调用失败/返回空时保持现状
     * （不删消息、不改摘要），下次请求会自然重试。
     */
    @Transactional
    public void compressIfNeeded(String conversationId) {
        if (messageRepository.countByConversationId(conversationId) <= MAX_MESSAGES) {
            return;
        }
        ChatConversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) {
            return;
        }
        List<ChatMessage> oldest = messageRepository.findByConversationIdOrderBySeqAsc(conversationId)
                .stream().limit(COMPRESS_BATCH).toList();
        if (oldest.isEmpty()) {
            return;
        }
        String summary;
        try {
            summary = sanitize(chatGateway.call(buildPrompt(conversation.getSummary(), oldest),
                    new LlmOptions(LlmFeature.MEMORY_COMPRESS)));
        } catch (Exception e) {
            log.warn("会话记忆压缩失败，保持现状（硬窗口截断兜底）。conversationId={} reason={}", conversationId, e.toString());
            return;
        }
        if (summary.isBlank()) {
            log.warn("会话记忆压缩返回空，保持现状。conversationId={}", conversationId);
            return;
        }
        conversation.setSummary(summary);
        conversationRepository.save(conversation);
        messageRepository.deleteAll(oldest);
    }

    /** 会话的早期对话摘要（供 system prompt 注入）；无摘要或会话不存在时为空 */
    public Optional<String> findSummary(String conversationId) {
        return conversationRepository.findById(conversationId)
                .map(ChatConversation::getSummary)
                .filter(s -> !s.isBlank());
    }

    /** 滚动合并 prompt：已有摘要 + 最旧一批对话原文 → 新摘要，保留事实/决定/待办/偏好/实体 */
    private static String buildPrompt(String existingSummary, List<ChatMessage> oldest) {
        String previous = existingSummary == null || existingSummary.isBlank() ? "（无）" : existingSummary;
        String dialogue = oldest.stream()
                .map(m -> ("USER".equals(m.getRole()) ? "用户：" : "助手：") + m.getContent())
                .collect(Collectors.joining("\n"));
        return String.format("""
                以下是某次对话已有的摘要和接下来更早的一段对话原文。请将二者合并为一份新的对话摘要。
                要求：
                - 保留关键事实、做出的决定、待办事项、用户偏好和重要实体（如任务/条目标题或 ID）
                - 与已有摘要合并去重，不是简单拼接
                - 只输出摘要本身，中文，不超过 %d 字

                已有摘要：%s

                早期对话原文：
                %s
                """, SUMMARY_MAX_CHARS, previous, dialogue);
    }

    /** 代码侧清洗：trim + 按 code point 截断（对齐 generateTitle 的双保险风格） */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String summary = raw.trim();
        return summary.codePoints().count() <= SUMMARY_MAX_CHARS
                ? summary
                : summary.substring(0, summary.offsetByCodePoints(0, SUMMARY_MAX_CHARS));
    }
}
