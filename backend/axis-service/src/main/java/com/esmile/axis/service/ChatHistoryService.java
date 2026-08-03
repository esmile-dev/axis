package com.esmile.axis.service;

import com.esmile.axis.entity.ChatConversation;
import com.esmile.axis.entity.ChatLongMemory;
import com.esmile.axis.entity.ChatMessage;
import com.esmile.axis.repository.ChatConversationRepository;
import com.esmile.axis.repository.ChatLongMemoryRepository;
import com.esmile.axis.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 聊天历史与长期记忆
 */
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatLongMemoryRepository longMemoryRepository;

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
        return longMemoryRepository.findTop50ByOrderByCreatedAtAsc();
    }

    public ChatLongMemory saveMemory(String content) {
        return longMemoryRepository.save(ChatLongMemory.builder().content(content).build());
    }

    public void deleteMemory(String id) {
        longMemoryRepository.deleteById(id);
    }
}
