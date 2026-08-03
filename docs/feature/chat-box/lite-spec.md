---
status: approved        # draft → approved → verified
feature: chat-box
created: 2026-07-26
---

# 精简规格：聊天框 /chat（M 级）

## 1. 需求

AI Agent 已有 `/api/agent/chat`（SSE + Tool Calling），但前端没有任何对话入口，用户无法用自然语言操作系统。本功能提供独立的 `/chat` 页面，并让工具调用过程对用户可见。

| 编号 | FR | 验收标准（当…时，系统应…） |
|------|-----|---------------------------|
| FR-001 | 流式对话 | 当用户在 `/chat` 发送消息时，系统应通过 SSE 逐 token 增量渲染助手回复 |
| FR-002 | 工具调用可见 | 当 Agent 调用 Inbox/Issue/Project/Knowledge 工具时，界面应在该条回复中显示「调用工具：xxx」状态行 |
| FR-003 | 会话记忆 | 当用户在同一 sessionId 下追问时，Agent 应能引用上文（后端 MessageChatMemoryAdvisor） |
| FR-004 | 新会话 | 当用户点击「新会话」时，系统应清空消息列表并更换 sessionId |
| FR-005 | 历史恢复 | 当用户刷新页面时，系统应从 localStorage 恢复消息记录（上限 100 条） |
| FR-006 | 入口 | 侧边栏导航最上方应有「Chat」入口 |
| FR-007 | 会话历史 | 当用户发送消息后，会话应持久化；/chat 左侧应展示历史会话列表，点击可切换并加载该会话消息 |
| FR-008 | 短期记忆 | 当后端重启后，同一会话的上下文仍应保留（ChatMemory 落库，窗口最近 100 条） |
| FR-009 | 长期记忆 | 当用户表达值得跨会话记住的偏好/事实时，Agent 应调用 saveMemory 保存，并在后续所有会话的 system prompt 中注入（上限 50 条） |
| FR-010 | 记忆管理 | 当用户在 /chat 打开「长期记忆」面板时，应可查看并逐条删除记忆 |

**范围外**：RAG/知识库语义搜索（等 pgvector spike 转正）、更早消息的归档查看（窗口外的历史消息会被裁剪覆盖）、危险工具操作的前端确认 UX（见变更记录）、全局浮层/CommandPalette 入口、多用户鉴权。

## 2. 方案要点

- 后端仅改 `AgentController.chat()`：SSE 帧由纯文本改为结构化 JSON——`{"type":"token","text"}` / `{"type":"tool","label"}` / `{"type":"done"}`；新增 `ToolCallNotifier`（单用户、单并发对话前提，`Sinks.Many` 持有当前请求 sink；无 sink 时 emit 空操作，不影响 `/expand`、digest 共用 Tool 的路径）；4 个 Tool 类 13 个方法首行插桩 `emit(中文动作描述)`。
- 持久化（2026-07-26 追加）：新表 `chat_conversation`（id 由前端 UUID 生成、title 先取首条用户消息截断 30 字兜底，首轮结束后异步 LLM 生成语义标题覆盖）、`chat_message`（conversationId + seq 排序）、`chat_long_memory`；`JpaChatMemoryRepository`（实现 Spring AI `ChatMemoryRepository`，saveAll 全量替换窗口内容、仅落 USER/ASSISTANT）替代 `InMemoryChatMemoryRepository`——短期记忆 = chat_message 滑动窗口（100 条），重启不丢；历史展示与记忆共用一表，窗口外旧消息被裁剪。
- 长期记忆：`MemoryTool`（saveMemory/listMemories/deleteMemory）+ 每次请求将记忆（≤50 条）注入 system prompt。**Prompt 与工具契约变更**（§7.2）：system prompt 增加记忆能力说明，chat/chatSync 工具列表新增 memoryTool。
- 新端点：`GET/DELETE /api/agent/conversations[/{id}]`、`GET /api/agent/conversations/{id}/messages`、`GET/DELETE /api/agent/memories[/{id}]`（ChatHistoryController，axis-service）。
- 前端新增 `useChat` composable（fetch + ReadableStream 解析 SSE，`$fetch` 不支持流式）与 `/chat` 页面（左栏会话列表 + 右栏聊天区 + 记忆管理 Dialog）；消息与会话以后端为准，不再用 localStorage。
- 接口变更：`POST /api/agent/chat` 响应帧格式变化（此前仅有内部使用，无其他前端依赖）。
- 评测方式（LLM 功能）：手动冒烟——发送「帮我在 Inbox 记一条测试」应出现 `tool` 帧且 Inbox 页面可见新条目；纯问答（如「你好」）应只有 `token` 帧；发送「记住：我喜欢 TypeScript」应出现「保存长期记忆」工具帧，新开会话提问应能引用该记忆。

## 3. 任务

| ✓ | 任务 | 验收点 | Commit |
|---|------|--------|--------|
| ☑ | ToolCallNotifier + AgentController 结构化 SSE | `mvn compile` 通过；curl 可见 tool/token/done 帧 | |
| ☑ | 4 个 Tool 类插桩 | 13 个方法均有 emit | |
| ☑ | useChat composable | 流式增量渲染、错误兜底 | |
| ☑ | /chat 页面 + 侧边栏入口（置顶） | 页面可用、构建通过 | |
| ☑ | chat 持久化三表 + JpaChatMemoryRepository | 编译通过；启动后 ddl-auto 建表；历史/记忆端点可用 | |
| ☑ | MemoryTool + prompt 注入 + 历史/记忆端点 | 编译通过；GET /api/agent/conversations、/api/agent/memories 200 | |
| ☑ | /chat 双栏改造 + 记忆管理 Dialog | 构建通过 | |

## 4. 验收记录

| FR | 结果 | 验证方式 |
|----|------|----------|
| FR-001~010 | 待验证 | 手动冒烟（需有效 AI key；当前两个 AI profile 均 401，见下） |

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-07-26 | 危险工具操作（删除等）暂无前端确认节点 | workflow §7.3 要求不可逆操作需人确认；本期豁免——工具层沿用既有自动执行行为，确认 UX 后续单独 M 级处理 |
| 2026-07-26 | 增加会话历史 + 短/长期记忆（FR-007~010）；system prompt 与工具列表变更 | 用户需求；prompt/工具契约变更按 §7.2 随本 M 级一并确认 |
| 2026-07-26 | 历史展示与短期记忆共用 chat_message 表，窗口（100 条）外旧消息被裁剪 | 避免双表同步复杂度；单用户场景 100 条/会话足够 |
| 2026-08-03 | 会话标题改为「截断兜底 + LLM 异步升级」：首轮结束后 `@Async` 生成语义标题覆盖截断标题，失败静默保留兜底；前端新会话发送后延迟 3s 再刷一次列表 | 截断标题无语义；同步生成会拖慢首条回复，故异步；prompt 契约新增标题生成 prompt（§7.2 随本 M 级确认）；评测入口 `TitleGenerationEval`（golden 15 条，长度通过率阈值 0.9，无 AI key 自动跳过） |
