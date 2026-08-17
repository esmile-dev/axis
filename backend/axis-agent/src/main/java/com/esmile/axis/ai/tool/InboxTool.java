package com.esmile.axis.ai.tool;

import com.esmile.axis.ai.ConfirmationService;
import com.esmile.axis.ai.ToolCallNotifier;
import com.esmile.axis.entity.InboxItem;
import com.esmile.axis.service.InboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent Tool：操作 Inbox（灵感回收站）
 */
@Component
@RequiredArgsConstructor
public class InboxTool {

    private final InboxService inboxService;
    private final ToolCallNotifier toolCallNotifier;
    private final ConfirmationService confirmationService;

    @Tool(description = "在 Inbox 中创建一条新的灵感/想法记录")
    public String createInboxItem(
            @ToolParam(description = "灵感/想法的内容") String content) {
        toolCallNotifier.emit("创建 Inbox 条目");
        InboxItem item = inboxService.create(content);
        return "✅ 已创建 Inbox 条目：" + item.getContent() + " (ID: " + item.getId() + ")";
    }

    @Tool(description = "列出 Inbox 中的条目，可按状态筛选")
    public String listInboxItems(
            @ToolParam(description = "状态筛选：TODO / DONE / all", required = false) String status) {
        toolCallNotifier.emit("列出 Inbox 条目");
        List<InboxItem> items = inboxService.findAll(status, null, null);
        if (items.isEmpty()) {
            return "📭 Inbox 中没有条目";
        }
        return items.stream()
                .map(item -> String.format("- [%s] %s (ID: %s)",
                        item.getStatus(), item.getContent(), item.getId()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "将 Inbox 条目标记为已完成（DONE）")
    public String markInboxDone(
            @ToolParam(description = "Inbox 条目的 ID") String id) {
        toolCallNotifier.emit("标记 Inbox 条目完成");
        inboxService.update(id, null, "DONE", false);
        return "✅ 已标记为完成";
    }

    @Tool(description = "删除一条 Inbox 条目（危险操作，需用户确认后才会执行）")
    public String deleteInboxItem(
            @ToolParam(description = "Inbox 条目的 ID") String id) {
        toolCallNotifier.emit("删除 Inbox 条目");
        InboxItem item = inboxService.findById(id);
        if (!confirmationService.awaitApproval("删除 Inbox 条目", "内容：" + preview(item.getContent()))) {
            return "⚠️ 用户未确认（拒绝或确认超时），已取消删除。";
        }
        inboxService.delete(id);
        return "🗑️ 已删除";
    }

    /** 确认卡片展示用摘要：按 code point 截 50 字，避免切烂 emoji 代理对 */
    private static String preview(String content) {
        return content.codePoints().count() > 50
                ? new String(content.codePoints().limit(50).toArray(), 0, 50) + "…"
                : content;
    }
}
