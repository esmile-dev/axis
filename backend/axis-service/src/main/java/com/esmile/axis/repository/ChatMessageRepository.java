package com.esmile.axis.repository;

import com.esmile.axis.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    List<ChatMessage> findByConversationIdOrderBySeqAsc(String conversationId);

    long deleteByConversationId(String conversationId);
}
