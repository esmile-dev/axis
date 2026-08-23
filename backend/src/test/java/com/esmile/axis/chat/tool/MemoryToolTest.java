package com.esmile.axis.chat.tool;

import com.esmile.axis.chat.ConfirmationService;
import com.esmile.axis.chat.ToolCallNotifier;
import com.esmile.axis.chat.ChatLongMemory;
import com.esmile.axis.chat.ChatHistoryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryToolTest {

    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
    private final ToolCallNotifier notifier = new ToolCallNotifier();
    private final ConfirmationService confirmationService = mock(ConfirmationService.class);
    private final MemoryTool tool = new MemoryTool(chatHistoryService, notifier, confirmationService);

    @Test
    void deleteMemory_approved_executesDelete() {
        when(chatHistoryService.findMemoryById("1"))
                .thenReturn(ChatLongMemory.builder().id("1").content("喜欢 TypeScript").build());
        when(confirmationService.awaitApproval(eq("删除长期记忆"), contains("喜欢 TypeScript"))).thenReturn(true);

        String result = tool.deleteMemory("1");

        verify(chatHistoryService).deleteMemory("1");
        assertThat(result).contains("已删除");
    }

    @Test
    void deleteMemory_rejected_skipsDelete() {
        when(chatHistoryService.findMemoryById("1"))
                .thenReturn(ChatLongMemory.builder().id("1").content("喜欢 TypeScript").build());
        when(confirmationService.awaitApproval(eq("删除长期记忆"), contains("喜欢 TypeScript"))).thenReturn(false);

        String result = tool.deleteMemory("1");

        verify(chatHistoryService, never()).deleteMemory("1");
        assertThat(result).contains("取消");
    }
}
