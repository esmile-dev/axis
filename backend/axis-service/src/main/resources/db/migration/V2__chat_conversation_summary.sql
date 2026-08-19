-- 会话早期对话的滚动摘要（超窗消息压缩产物），由 ConversationSummaryService 维护
ALTER TABLE chat_conversation ADD COLUMN summary text;
