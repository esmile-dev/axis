package com.esmile.axis.repository;

import com.esmile.axis.entity.ChatLongMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatLongMemoryRepository extends JpaRepository<ChatLongMemory, String> {

    List<ChatLongMemory> findTop50ByOrderByCreatedAtAsc();
}
