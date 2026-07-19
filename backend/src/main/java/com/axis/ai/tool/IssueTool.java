package com.axis.ai.tool;

import com.axis.entity.Issue;
import com.axis.service.IssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent Tool：操作 Issue（任务/需求）
 */
@Component
@RequiredArgsConstructor
public class IssueTool {

    private final IssueService issueService;

    @Tool(description = "创建一个新的 Issue（任务/需求）")
    public String createIssue(
            @ToolParam(description = "Issue 标题") String title,
            @ToolParam(description = "Issue 描述", required = false) String description,
            @ToolParam(description = "优先级：NONE/LOW/MEDIUM/HIGH/URGENT", required = false) String priority,
            @ToolParam(description = "类型：BUG/FEATURE/IMPROVEMENT", required = false) String type,
            @ToolParam(description = "所属项目的 ID", required = false) String projectId) {
        Issue issue = issueService.create(title, description, null, priority, type, null, projectId, null);
        return String.format("✅ 已创建 Issue：%s\n  优先级: %s | 类型: %s | ID: %s",
                issue.getTitle(), issue.getPriority(), issue.getType(), issue.getId());
    }

    @Tool(description = "列出 Issue 列表，可按项目筛选")
    public String listIssues(
            @ToolParam(description = "项目 ID 筛选，传 'none' 查未归属项目的 Issue", required = false) String projectId) {
        List<Issue> issues = issueService.findAll(projectId);
        if (issues.isEmpty()) {
            return "📋 没有找到 Issue";
        }
        return issues.stream()
                .map(i -> String.format("- [%s] %s (优先级: %s, ID: %s)",
                        i.getStatus(), i.getTitle(), i.getPriority(), i.getId()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "更新 Issue 的状态")
    public String updateIssueStatus(
            @ToolParam(description = "Issue 的 ID") String id,
            @ToolParam(description = "新状态：BACKLOG/TODO/IN_PROGRESS/DONE/CANCELLED") String status) {
        issueService.update(id, null, null, status, null, null, null, null, null);
        return "✅ Issue 状态已更新为 " + status;
    }

    @Tool(description = "更新 Issue 的优先级")
    public String updateIssuePriority(
            @ToolParam(description = "Issue 的 ID") String id,
            @ToolParam(description = "新优先级：NONE/LOW/MEDIUM/HIGH/URGENT") String priority) {
        issueService.update(id, null, null, null, priority, null, null, null, null);
        return "✅ Issue 优先级已更新为 " + priority;
    }

    @Tool(description = "删除一个 Issue")
    public String deleteIssue(
            @ToolParam(description = "Issue 的 ID") String id) {
        issueService.delete(id);
        return "🗑️ Issue 已删除";
    }
}
