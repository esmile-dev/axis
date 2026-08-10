package com.esmile.axis.repository;

import com.esmile.axis.entity.ChatLongMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatLongMemoryRepository extends JpaRepository<ChatLongMemory, String> {

    /** 取最新 50 条（Asc 会让第 51 条起的记忆永远进不了 system prompt） */
    List<ChatLongMemory> findTop50ByOrderByCreatedAtDesc();
}
