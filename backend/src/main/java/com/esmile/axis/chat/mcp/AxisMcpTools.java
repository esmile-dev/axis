package com.esmile.axis.chat.mcp;

import com.esmile.axis.project.Comment;
import com.esmile.axis.project.Issue;
import com.esmile.axis.project.IssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP 工具面（agent-dispatch Phase 1）：暴露给本地 coding agent（Claude Code 等）的收敛接口，
 * 经 {@link McpServerConfig} 声明的唯一 ToolCallbackProvider 进入 MCP server（端点 /mcp）。
 * 与内部 ai/tool/ 工具（ChatClient 按次绑定）天然隔离；DONE/CANCELLED 永远由人在 UI 操作。
 */
@Component
@RequiredArgsConstructor
public class AxisMcpTools {

    /** agent 可流转的状态白名单 */
    private static final Set<String> ALLOWED_STATUSES = Set.of("BACKLOG", "TODO", "IN_PROGRESS");

    private final IssueService issueService;

    @Tool(name = "get_issue", description = "读取 Issue 详情（标题、描述、状态、优先级、评论）")
    public String getIssue(@ToolParam(description = "Issue 的 ID") String id) {
        Issue issue = issueService.findById(id);
        List<Comment> comments = issueService.findComments(id);
        String detail = String.format("# %s%n状态: %s | 优先级: %s | 类型: %s | ID: %s%n%n%s",
                issue.getTitle(), issue.getStatus(), issue.getPriority(), issue.getType(),
                issue.getId(), issue.getDescription() == null ? "（无描述）" : issue.getDescription());
        if (comments.isEmpty()) {
            return detail;
        }
        return detail + "\n\n## 评论\n" + comments.stream()
                .map(c -> "- " + c.getContent())
                .collect(Collectors.joining("\n"));
    }

    @Tool(name = "add_issue_comment", description = "给 Issue 添加评论（完成汇报：改动摘要、跑过的测试、遗留问题）")
    public String addIssueComment(@ToolParam(description = "Issue 的 ID") String id,
                                  @ToolParam(description = "评论内容") String content) {
        issueService.addComment(id, content);
        return "✅ 已添加评论到 Issue " + id;
    }

    @Tool(name = "transition_issue_status", description = "流转 Issue 状态（仅允许 BACKLOG/TODO/IN_PROGRESS；DONE/CANCELLED 由人在 UI 操作）")
    public String transitionIssueStatus(@ToolParam(description = "Issue 的 ID") String id,
                                        @ToolParam(description = "目标状态：BACKLOG/TODO/IN_PROGRESS") String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            return "⚠️ 已拒绝：不允许把 Issue 流转为 " + status
                    + "（agent 仅可流转 BACKLOG/TODO/IN_PROGRESS；DONE/CANCELLED 由人在 UI 操作）";
        }
        issueService.update(id, null, null, normalized, null, null, null, null, null);
        return "✅ Issue 状态已流转为 " + normalized;
    }
}
