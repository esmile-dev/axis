package com.esmile.axis.config;

import com.esmile.axis.entity.ChatConversation;
import com.esmile.axis.entity.ChatMessage;
import com.esmile.axis.repository.ChatConversationRepository;
import com.esmile.axis.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JPA 版 ChatMemoryRepository — 短期记忆持久化到 chat_message 表，
 * 替代 InMemoryChatMemoryRepository（重启即丢），同时支撑前端历史记录展示。
 *
 * <p>MessageWindowChatMemory 每次 saveAll 传入裁剪后的完整窗口，这里全量替换；
 * SYSTEM/TOOL 等中间消息不落库（回放时只还原 USER/ASSISTANT 纯文本）。
 */
@Component
@RequiredArgsConstructor
public class JpaChatMemoryRepository implements ChatMemoryRepository {

    private static final int TITLE_MAX = 30;

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;

    @Override
    public List<String> findConversationIds() {
        return conversationRepository.findAll().stream().map(ChatConversation::getId).toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return messageRepository.findByConversationIdOrderBySeqAsc(conversationId).stream()
                .map(this::toSpringMessage)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        upsertConversation(conversationId, messages);
        messageRepository.deleteByConversationId(conversationId);
        List<ChatMessage> entities = new ArrayList<>();
        int seq = 0;
        for (Message message : messages) {
            MessageType type = message.getMessageType();
            if (type != MessageType.USER && type != MessageType.ASSISTANT) {
                continue;
            }
            String text = message.getText() == null ? "" : message.getText();
            entities.add(ChatMessage.builder()
                    .conversationId(conversationId)
                    .role(type.name())
                    .content(text)
                    .seq(seq++)
                    .build());
        }
        messageRepository.saveAll(entities);
    }

    @Override
    @Transactional
    public void deleteByConversationId(String conversationId) {
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.deleteById(conversationId);
    }

    private Optional<Message> toSpringMessage(ChatMessage entity) {
        return switch (entity.getRole()) {
            case "USER" -> Optional.of(new UserMessage(entity.getContent()));
            case "ASSISTANT" -> Optional.of(new AssistantMessage(entity.getContent()));
            default -> Optional.empty();
        };
    }

    private void upsertConversation(String conversationId, List<Message> messages) {
        ChatConversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) {
            conversationRepository.save(ChatConversation.builder()
                    .id(conversationId)
                    .title(deriveTitle(messages))
                    .build());
        } else {
            conversation.setUpdatedAt(Instant.now());
            conversationRepository.save(conversation);
        }
    }

    /** 用首条用户消息作为会话标题（LLM 语义标题生成前的截断兜底，按 code point 截断避免切乱 emoji） */
    private String deriveTitle(List<Message> messages) {
        return messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .map(Message::getText)
                .filter(t -> t != null && !t.isBlank())
                .findFirst()
                .map(t -> t.codePoints().count() <= TITLE_MAX
                        ? t
                        : t.substring(0, t.offsetByCodePoints(0, TITLE_MAX)) + "…")
                .orElse("新会话");
    }
}
