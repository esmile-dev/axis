package com.esmile.axis.repository;

import com.esmile.axis.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    List<ChatMessage> findByConversationIdOrderBySeqAsc(String conversationId);

    Optional<ChatMessage> findTopByConversationIdAndRoleOrderBySeqDesc(String conversationId, String role);

    long deleteByConversationId(String conversationId);
}
