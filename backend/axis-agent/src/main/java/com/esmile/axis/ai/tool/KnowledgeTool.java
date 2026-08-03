package com.esmile.axis.ai.tool;

import com.esmile.axis.ai.ToolCallNotifier;
import com.esmile.axis.knowledge.KnowledgeType;
import com.esmile.axis.knowledge.entity.KnowledgeItem;
import com.esmile.axis.knowledge.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent Tool：操作 Knowledge（知识库）
 */
@Component
@RequiredArgsConstructor
public class KnowledgeTool {

    private final KnowledgeItemRepository knowledgeItemRepository;
    private final ToolCallNotifier toolCallNotifier;

    @Tool(description = "在知识库中创建一篇新文档")
    public String createDocument(
            @ToolParam(description = "文档标题") String title,
            @ToolParam(description = "文档内容（Markdown 格式）") String content) {
        toolCallNotifier.emit("创建知识文档");
        KnowledgeItem item = knowledgeItemRepository.save(KnowledgeItem.builder()
                .type(KnowledgeType.NOTE)
                .title(title)
                .content(content)
                .build());
        return String.format("✅ 已创建知识文档：%s (ID: %s)", item.getTitle(), item.getId());
    }

    @Tool(description = "列出知识库中的所有文档")
    public String listDocuments() {
        toolCallNotifier.emit("列出知识文档");
        List<KnowledgeItem> docs = knowledgeItemRepository.findAllByOrderByCreatedAtDesc();
        if (docs.isEmpty()) {
            return "📚 知识库为空";
        }
        return docs.stream()
                .map(d -> String.format("- %s (ID: %s)", d.getTitle(), d.getId()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "搜索知识库文档内容（关键词搜索）")
    public String searchDocuments(
            @ToolParam(description = "搜索关键词") String keyword) {
        toolCallNotifier.emit("搜索知识库");
        List<KnowledgeItem> docs = knowledgeItemRepository.findAllByOrderByCreatedAtDesc();
        List<KnowledgeItem> matched = docs.stream()
                .filter(d -> d.getTitle().toLowerCase().contains(keyword.toLowerCase())
                        || d.getContent().toLowerCase().contains(keyword.toLowerCase()))
                .toList();
        if (matched.isEmpty()) {
            return "🔍 没有找到匹配 \"" + keyword + "\" 的文档";
        }
        return matched.stream()
                .map(d -> String.format("- %s (ID: %s)\n  摘要: %s...",
                        d.getTitle(), d.getId(),
                        d.getContent().substring(0, Math.min(100, d.getContent().length()))))
                .collect(Collectors.joining("\n"));
    }
}
