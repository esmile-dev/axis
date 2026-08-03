package com.esmile.axis.repository;

import com.esmile.axis.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, String> {

    List<ChatConversation> findAllByOrderByUpdatedAtDesc();
}
