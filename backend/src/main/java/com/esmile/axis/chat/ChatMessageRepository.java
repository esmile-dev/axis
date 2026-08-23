package com.esmile.axis.chat;

import com.esmile.axis.chat.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    List<ChatMessage> findByConversationIdOrderBySeqAsc(String conversationId);

    Optional<ChatMessage> findTopByConversationIdAndRoleOrderBySeqDesc(String conversationId, String role);

    long countByConversationId(String conversationId);

    long deleteByConversationId(String conversationId);
}
