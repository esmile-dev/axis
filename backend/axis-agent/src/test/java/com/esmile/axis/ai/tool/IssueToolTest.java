package com.esmile.axis.ai.tool;

import com.esmile.axis.ai.ConfirmationService;
import com.esmile.axis.ai.ToolCallNotifier;
import com.esmile.axis.entity.Issue;
import com.esmile.axis.service.IssueService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueToolTest {

    private final IssueService issueService = mock(IssueService.class);
    private final ToolCallNotifier notifier = new ToolCallNotifier();
    private final ConfirmationService confirmationService = mock(ConfirmationService.class);
    private final IssueTool tool = new IssueTool(issueService, notifier, confirmationService);

    @Test
    void deleteIssue_approved_executesDelete() {
        when(issueService.findById("1")).thenReturn(Issue.builder().id("1").title("修复登录 bug").build());
        when(confirmationService.awaitApproval(eq("删除 Issue"), contains("修复登录 bug"))).thenReturn(true);

        String result = tool.deleteIssue("1");

        verify(issueService).delete("1");
        assertThat(result).contains("已删除").contains("修复登录 bug");
    }

    @Test
    void deleteIssue_rejected_skipsDelete() {
        when(issueService.findById("1")).thenReturn(Issue.builder().id("1").title("修复登录 bug").build());
        when(confirmationService.awaitApproval(eq("删除 Issue"), contains("修复登录 bug"))).thenReturn(false);

        String result = tool.deleteIssue("1");

        verify(issueService, never()).delete("1");
        assertThat(result).contains("取消");
    }
}
