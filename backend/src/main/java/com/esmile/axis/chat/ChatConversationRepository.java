package com.esmile.axis.chat;

import com.esmile.axis.chat.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, String> {

    List<ChatConversation> findAllByOrderByUpdatedAtDesc();
}
