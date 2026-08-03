package com.esmile.axis.ai.tool;

import com.esmile.axis.ai.ToolCallNotifier;
import com.esmile.axis.entity.ChatLongMemory;
import com.esmile.axis.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent Tool：长期记忆（跨会话记住用户偏好/重要事实）
 */
@Component
@RequiredArgsConstructor
public class MemoryTool {

    private final ChatHistoryService chatHistoryService;
    private final ToolCallNotifier toolCallNotifier;

    @Tool(description = "保存一条长期记忆（用户的偏好、习惯、重要事实等需要跨会话记住的信息）")
    public String saveMemory(
            @ToolParam(description = "要记住的内容，一句话概括") String content) {
        toolCallNotifier.emit("保存长期记忆");
        ChatLongMemory memory = chatHistoryService.saveMemory(content);
        return "🧠 已记住：" + memory.getContent() + " (ID: " + memory.getId() + ")";
    }

    @Tool(description = "列出所有长期记忆")
    public String listMemories() {
        toolCallNotifier.emit("列出长期记忆");
        List<ChatLongMemory> memories = chatHistoryService.listMemories();
        if (memories.isEmpty()) {
            return "🧠 还没有长期记忆";
        }
        return memories.stream()
                .map(m -> String.format("- %s (ID: %s)", m.getContent(), m.getId()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "删除一条长期记忆")
    public String deleteMemory(
            @ToolParam(description = "记忆的 ID") String id) {
        toolCallNotifier.emit("删除长期记忆");
        chatHistoryService.deleteMemory(id);
        return "🗑️ 已删除该记忆";
    }
}
