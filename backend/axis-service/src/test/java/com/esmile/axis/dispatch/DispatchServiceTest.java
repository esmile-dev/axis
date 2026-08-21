package com.esmile.axis.dispatch;

import com.esmile.axis.entity.Issue;
import com.esmile.axis.entity.Project;
import com.esmile.axis.repository.IssueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 派发校验（404/400 四条路径）+ prompt 组装（覆盖/默认/无描述）+ terminal 解析（默认/未知/显式）。
 */
class DispatchServiceTest {

    private final IssueRepository issueRepository = mock(IssueRepository.class);
    private final TerminalLauncher terminalLauncher = mock(TerminalLauncher.class);

    private final DispatchService service = new DispatchService(issueRepository, terminalLauncher);

    private static final String MCP_SUFFIX = "\n\n---\n任务来自 Axis 任务系统（issue id: i1）。"
            + "完成后请用 axis MCP 工具回写：调用 add_issue_comment 提交完成汇报（改动摘要、跑过的测试、遗留问题）；"
            + "需要核对需求细节可用 get_issue。";

    @TempDir
    Path repoDir;

    private Issue stubIssue(Project project) {
        Issue issue = Issue.builder().id("i1").title("修复登录页").description("把按钮改成蓝色").build();
        issue.setProject(project);
        when(issueRepository.findById("i1")).thenReturn(Optional.of(issue));
        return issue;
    }

    private static Project projectWithRepoPath(String repoPath) {
        return Project.builder().name("axis").repoPath(repoPath).build();
    }

    @Test
    void issueNotFound() {
        when(issueRepository.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.dispatch("missing", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void issueWithoutProject() {
        stubIssue(null);
        assertThatThrownBy(() -> service.dispatch("i1", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void projectWithoutRepoPath() {
        stubIssue(projectWithRepoPath(null));
        assertThatThrownBy(() -> service.dispatch("i1", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void repoPathNotExistingDirectory() {
        stubIssue(projectWithRepoPath("/nonexistent/axis-dispatch-test"));
        assertThatThrownBy(() -> service.dispatch("i1", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void defaultPromptIsTitlePlusDescription() {
        stubIssue(projectWithRepoPath(repoDir.toString()));
        service.dispatch("i1", null, null);
        verify(terminalLauncher).launch(eq(repoDir.toString()), eq("修复登录页\n\n把按钮改成蓝色" + MCP_SUFFIX), eq(DispatchTerminal.TERMINAL));
    }

    @Test
    void defaultPromptWithoutDescriptionIsTitleOnly() {
        Issue issue = stubIssue(projectWithRepoPath(repoDir.toString()));
        issue.setDescription("  ");
        service.dispatch("i1", " ", null);
        verify(terminalLauncher).launch(eq(repoDir.toString()), eq("修复登录页" + MCP_SUFFIX), eq(DispatchTerminal.TERMINAL));
    }

    @Test
    void explicitPromptOverridesDefault() {
        stubIssue(projectWithRepoPath(repoDir.toString()));
        service.dispatch("i1", "自定义 prompt", null);
        verify(terminalLauncher).launch(eq(repoDir.toString()), eq("自定义 prompt"), eq(DispatchTerminal.TERMINAL));
    }

    @Test
    void unknownTerminalRejected() {
        stubIssue(projectWithRepoPath(repoDir.toString()));
        assertThatThrownBy(() -> service.dispatch("i1", null, "iterm"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void explicitWarpPassedThrough() {
        stubIssue(projectWithRepoPath(repoDir.toString()));
        service.dispatch("i1", null, "warp");
        verify(terminalLauncher).launch(eq(repoDir.toString()), eq("修复登录页\n\n把按钮改成蓝色" + MCP_SUFFIX), eq(DispatchTerminal.WARP));
    }
}
