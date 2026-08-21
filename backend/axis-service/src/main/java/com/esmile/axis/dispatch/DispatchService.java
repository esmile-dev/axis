package com.esmile.axis.dispatch;

import com.esmile.axis.entity.Issue;
import com.esmile.axis.entity.Project;
import com.esmile.axis.repository.IssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动器交接（agent-dispatch Phase 0）：校验 Issue 所属 Project 的工作目录，
 * 组装 prompt（默认 = 标题 + 描述），交给 {@link TerminalLauncher} 在本机终端打开 Claude。
 */
@Service
@RequiredArgsConstructor
public class DispatchService {

    private final IssueRepository issueRepository;
    private final TerminalLauncher terminalLauncher;

    @Transactional(readOnly = true)
    public void dispatch(String issueId, String prompt, String terminal) {
        Issue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found: " + issueId));
        Project project = issue.getProject();
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Issue has no project; assign it to a project with repo path first");
        }
        String repoPath = project.getRepoPath();
        if (repoPath == null || repoPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project has no repo path configured: " + project.getName());
        }
        if (!Files.isDirectory(Path.of(repoPath))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repo path is not an existing directory: " + repoPath);
        }
        terminalLauncher.launch(repoPath, (prompt != null && !prompt.isBlank()) ? prompt : defaultPrompt(issue), parseTerminal(terminal));
    }

    private static DispatchTerminal parseTerminal(String terminal) {
        if (terminal == null || terminal.isBlank()) {
            return DispatchTerminal.TERMINAL;
        }
        try {
            return DispatchTerminal.valueOf(terminal.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown terminal: " + terminal);
        }
    }

    private static String defaultPrompt(Issue issue) {
        if (issue.getDescription() == null || issue.getDescription().isBlank()) {
            return issue.getTitle();
        }
        return issue.getTitle() + "\n\n" + issue.getDescription();
    }
}
