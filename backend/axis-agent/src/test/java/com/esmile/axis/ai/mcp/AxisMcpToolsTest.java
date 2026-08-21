package com.esmile.axis.ai.mcp;

import com.esmile.axis.entity.Comment;
import com.esmile.axis.entity.Issue;
import com.esmile.axis.enums.IssuePriority;
import com.esmile.axis.enums.IssueStatus;
import com.esmile.axis.enums.IssueType;
import com.esmile.axis.service.IssueService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * get_issue 详情组装、add_issue_comment 委托、状态流转白名单（内外各路径 + 大小写归一）。
 */
class AxisMcpToolsTest {

    private final IssueService issueService = mock(IssueService.class);
    private final AxisMcpTools tools = new AxisMcpTools(issueService);

    private void stubIssue() {
        Issue issue = Issue.builder()
                .id("i1").title("修复登录页").description("把按钮改成蓝色")
                .status(IssueStatus.TODO).priority(IssuePriority.HIGH).type(IssueType.BUG)
                .build();
        when(issueService.findById("i1")).thenReturn(issue);
    }

    @Test
    void getIssueReturnsDetailsAndComments() {
        stubIssue();
        when(issueService.findComments("i1")).thenReturn(List.of(
                Comment.builder().content("第一条").build(),
                Comment.builder().content("第二条").build()));

        String result = tools.getIssue("i1");

        assertThat(result).contains("修复登录页", "把按钮改成蓝色", "TODO", "HIGH", "BUG", "第一条", "第二条");
    }

    @Test
    void getIssueWithoutCommentsOmitsSection() {
        stubIssue();
        when(issueService.findComments("i1")).thenReturn(List.of());

        String result = tools.getIssue("i1");

        assertThat(result).contains("修复登录页").doesNotContain("## 评论");
    }

    @Test
    void addIssueCommentDelegates() {
        String result = tools.addIssueComment("i1", "完成汇报");

        verify(issueService).addComment("i1", "完成汇报");
        assertThat(result).contains("✅");
    }

    @Test
    void transitionAllowedStatusDelegates() {
        String result = tools.transitionIssueStatus("i1", "IN_PROGRESS");

        verify(issueService).update(eq("i1"), isNull(), isNull(), eq("IN_PROGRESS"),
                isNull(), isNull(), isNull(), isNull(), isNull());
        assertThat(result).contains("IN_PROGRESS");
    }

    @Test
    void transitionLowercaseNormalized() {
        String result = tools.transitionIssueStatus("i1", " in_progress ");

        verify(issueService).update(eq("i1"), isNull(), isNull(), eq("IN_PROGRESS"),
                isNull(), isNull(), isNull(), isNull(), isNull());
        assertThat(result).contains("IN_PROGRESS");
    }

    @Test
    void transitionDoneRejected() {
        String result = tools.transitionIssueStatus("i1", "DONE");

        assertThat(result).contains("拒绝").contains("DONE");
        verifyNoInteractions(issueService);
    }

    @Test
    void transitionCancelledRejected() {
        assertThat(tools.transitionIssueStatus("i1", "CANCELLED")).contains("拒绝");
        verifyNoInteractions(issueService);
    }

    @Test
    void transitionGarbageRejected() {
        assertThat(tools.transitionIssueStatus("i1", "whatever")).contains("拒绝");
        verifyNoInteractions(issueService);
    }
}
