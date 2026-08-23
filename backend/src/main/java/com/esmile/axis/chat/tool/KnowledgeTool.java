package com.esmile.axis.chat.tool;

import com.esmile.axis.chat.ToolCallNotifier;
import com.esmile.axis.knowledge.KnowledgeService;
import com.esmile.axis.knowledge.dto.KnowledgeItemSummaryView;
import com.esmile.axis.knowledge.search.KnowledgeSearchHit;
import com.esmile.axis.knowledge.search.KnowledgeSearchService;
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

    private final KnowledgeService knowledgeService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final ToolCallNotifier toolCallNotifier;

    @Tool(description = "在知识库中创建一篇新文档")
    public String createDocument(
            @ToolParam(description = "文档标题") String title,
            @ToolParam(description = "文档内容（Markdown 格式）") String content) {
        toolCallNotifier.emit("创建知识文档");
        KnowledgeItemSummaryView item = knowledgeService.createNote(title, content);
        return String.format("✅ 已创建知识文档：%s (ID: %s)", item.title(), item.id());
    }

    @Tool(description = "列出知识库中的所有文档")
    public String listDocuments() {
        toolCallNotifier.emit("列出知识文档");
        List<KnowledgeItemSummaryView> docs = knowledgeService.list(null, null, null, null);
        if (docs.isEmpty()) {
            return "📚 知识库为空";
        }
        return docs.stream()
                .map(d -> String.format("- %s (ID: %s)", d.title(), d.id()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "语义搜索知识库文档：按内容语义匹配，返回最相关的 top-5 文档（标题 + 内容片段 + ID）；向量检索不可用时自动降级为关键词匹配")
    public String searchDocuments(
            @ToolParam(description = "搜索查询（自然语言或关键词）") String keyword) {
        toolCallNotifier.emit("搜索知识库");
        List<KnowledgeSearchHit> hits = knowledgeSearchService.search(keyword);
        if (hits.isEmpty()) {
            return "🔍 没有找到匹配 \"" + keyword + "\" 的文档";
        }
        return hits.stream()
                .map(h -> String.format("- %s (ID: %s)\n  摘要: %s...", h.title(), h.itemId(), h.snippet()))
                .collect(Collectors.joining("\n"));
    }
}
