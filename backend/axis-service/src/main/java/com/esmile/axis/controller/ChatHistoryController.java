package com.esmile.axis.controller;

import com.esmile.axis.entity.ChatConversation;
import com.esmile.axis.entity.ChatLongMemory;
import com.esmile.axis.entity.ChatMessage;
import com.esmile.axis.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 聊天历史与长期记忆 API
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    @GetMapping("/conversations")
    public List<ChatConversation> listConversations() {
        return chatHistoryService.listConversations();
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ChatMessage> listMessages(@PathVariable String id) {
        return chatHistoryService.listMessages(id);
    }

    @DeleteMapping("/conversations/{id}")
    public void deleteConversation(@PathVariable String id) {
        chatHistoryService.deleteConversation(id);
    }

    @GetMapping("/memories")
    public List<ChatLongMemory> listMemories() {
        return chatHistoryService.listMemories();
    }

    @DeleteMapping("/memories/{id}")
    public void deleteMemory(@PathVariable String id) {
        chatHistoryService.deleteMemory(id);
    }
}
