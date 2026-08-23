package com.esmile.axis.chat.tool;

import com.esmile.axis.chat.ConfirmationService;
import com.esmile.axis.chat.ToolCallNotifier;
import com.esmile.axis.inbox.InboxItem;
import com.esmile.axis.inbox.InboxService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboxToolTest {

    private final InboxService inboxService = mock(InboxService.class);
    private final ToolCallNotifier notifier = new ToolCallNotifier();
    private final ConfirmationService confirmationService = mock(ConfirmationService.class);
    private final InboxTool tool = new InboxTool(inboxService, notifier, confirmationService);

    @Test
    void deleteInboxItem_approved_executesDelete() {
        when(inboxService.findById("1")).thenReturn(InboxItem.builder().id("1").content("灵感：做个快捷键").build());
        when(confirmationService.awaitApproval(eq("删除 Inbox 条目"), contains("灵感：做个快捷键"))).thenReturn(true);

        String result = tool.deleteInboxItem("1");

        verify(inboxService).delete("1");
        assertThat(result).contains("已删除");
    }

    @Test
    void deleteInboxItem_rejected_skipsDelete() {
        when(inboxService.findById("1")).thenReturn(InboxItem.builder().id("1").content("灵感：做个快捷键").build());
        when(confirmationService.awaitApproval(eq("删除 Inbox 条目"), contains("灵感：做个快捷键"))).thenReturn(false);

        String result = tool.deleteInboxItem("1");

        verify(inboxService, never()).delete("1");
        assertThat(result).contains("取消");
    }
}
