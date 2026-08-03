package com.esmile.axis.ai.tool;

import com.esmile.axis.ai.ToolCallNotifier;
import com.esmile.axis.entity.Project;
import com.esmile.axis.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent Tool：操作 Project（项目管理）
 */
@Component
@RequiredArgsConstructor
public class ProjectTool {

    private final ProjectService projectService;
    private final ToolCallNotifier toolCallNotifier;

    @Tool(description = "创建一个新项目")
    public String createProject(
            @ToolParam(description = "项目名称") String name,
            @ToolParam(description = "项目描述", required = false) String description) {
        toolCallNotifier.emit("创建项目");
        Project project = projectService.create(name, description, null, null);
        return String.format("✅ 已创建项目：%s (ID: %s)", project.getName(), project.getId());
    }

    @Tool(description = "列出所有项目")
    public String listProjects() {
        toolCallNotifier.emit("列出项目");
        List<Project> projects = projectService.findAll();
        if (projects.isEmpty()) {
            return "📁 没有项目";
        }
        return projects.stream()
                .map(p -> String.format("- %s [%s] (ID: %s)", p.getName(), p.getStatus(), p.getId()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "更新项目状态")
    public String updateProjectStatus(
            @ToolParam(description = "项目的 ID") String id,
            @ToolParam(description = "新状态：PLANNING/ACTIVE/COMPLETED/ARCHIVED") String status) {
        toolCallNotifier.emit("更新项目状态");
        projectService.update(id, null, null, status, null);
        return "✅ 项目状态已更新为 " + status;
    }
}
