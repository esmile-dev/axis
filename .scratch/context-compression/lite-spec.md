---
status: verified       # draft → approved → verified
feature: context-compression
created: 2026-08-19
---

# 精简规格：会话上下文压缩——滚动摘要替代硬窗口截断（M 级）

## 1. 需求

背景：短期记忆是 `MessageWindowChatMemory`（maxMessages=100）硬窗口，超窗的早期消息在 `saveAll` 全量替换时被物理删除，LLM 与用户都永久丢失该上下文。升级为"窗口 + 滚动摘要"：超窗旧消息由 LLM 压缩成摘要注入 system prompt，近期消息仍走原文。

| 编号 | FR | 验收标准（当…时，系统应…） |
|------|-----|---------------------------|
| FR-001 | 预压缩触发：`AgentService.chat`/`chatSync` 处理请求前，若该会话 `chat_message` 数 > 100，取最旧 30 条与已有摘要一并压缩为新摘要，写回 `chat_conversation.summary` 并删除这 30 条 | 单测：超阈值时压缩最旧批、写回、删除；未超阈值零动作（不调 LLM） |
| FR-002 | 滚动合并：已有摘要时新摘要与之合并去重，非简单拼接；prompt 约束 ≤400 字 + 代码侧截断双保险 | 单测：prompt 含旧摘要；超长输出被截断 |
| FR-003 | 摘要注入：该会话有摘要时 `systemPrompt` 追加"早期对话摘要"段（在长期记忆段之后）；无摘要时 system prompt 不变 | 单测：两分支各一 |
| FR-004 | 降级：压缩 LLM 调用失败/返回空 → 不删消息、不改摘要，WARN 日志，行为退化为现状（硬截断） | 单测：异常路径消息与摘要均不变 |
| FR-005 | 可观测：压缩调用走 `ChatGateway` + 新增 `LlmFeature.MEMORY_COMPRESS`，自动进 `llm_call_log` 与用量面板 | 单测断言 options feature；用量面板按 feature 聚合自然覆盖 |
| FR-006 | 可评测（§7.1 原则）：`ConversationCompressionEval` 合成对话压缩，断言关键事实关键词命中 + 长度上限 | 有 key 时跑过；无 key `assumeTrue` 跳过不红 CI |

**范围外**：摘要前端展示；向量化检索历史（策略谱系另一支）；按 token 精确计量触发（消息数阈值足够）；多用户并发（项目单用户假设）；自动重试压缩失败（下次请求自然再触发）。

## 2. 方案要点

- **预压缩（pre-compression）**：请求开始前检查并压缩最旧 30 条，窗口留 30 条余量——`MessageWindowChatMemory` 内部裁剪实际不触发，语义零改动。否决自定义 `ChatMemory`（advisor 内部裁剪无 evict 钩子，流式聚合线程上塞阻塞 LLM 调用竞态复杂）。
- 新增 `ConversationSummaryService`（axis-service `service/`）：`compressIfNeeded`（@Transactional，对齐 `generateAndUpgradeTitle` 既有风格）+ `findSummary`。常量 `MAX_MESSAGES=100` / `COMPRESS_BATCH=30` / `SUMMARY_MAX_CHARS=400` 不设配置项；`AiConfig` 的 `maxMessages` 引用同一常量（单一事实源）。
- 表变更：`chat_conversation` 加 `summary text`（可空）——**走 V2 增量迁移**（保留本地数据，见变更记录），应用/测试启动时 Flyway 自动应用。
- `saveAll` 全量删插 + seq 重排与预删最旧批天然一致；retry 去重路径不受影响。
- 失败即现状：压缩失败不删消息，窗口硬截断兜底，无任何路径比今天更差。

## 3. 任务

| ✓ | 任务 | 验收点 | Commit |
|---|------|--------|--------|
| ☑ | `V2__chat_conversation_summary.sql` + `ChatConversation.summary`；`LlmFeature.MEMORY_COMPRESS`；`countByConversationId` | 编译过 ✅ | |
| ☑ | `ConversationSummaryServiceTest` 8 用例先红 → `ConversationSummaryService` 实现转绿 | FR-001/002/004/005 ✅ | |
| ☑ | `AgentService` 两处触发 + `systemPrompt(sessionId)` 注入；`AiConfig` 引用常量；`AgentServiceTest` +2 | 全量 mvn test 绿 ✅ | |
| ☑ | `ConversationCompressionEval`（真实 LLM） | 关键词 3/3 + 滚动合并去重通过 ✅ | |
| ☑ | E2E（7790 端口实例 + 101 条消息会话） | 摘要生成/删 30 剩 73/凭摘要答出已删原文 ✅ | |
| ☑ | career 三件套 + AGENTS.md + CONTEXT.md 同步 | 文档与代码一致 ✅ | |

## 4. 验收记录

| FR | 结果 | 验证方式 |
|----|------|----------|
| FR-001 | 通过 | `ConversationSummaryServiceTest`（超阈值压缩最旧 30 条 + 写回 + 删除；未超零动作）；E2E：101 条 → summary 写入、剩 73 条 |
| FR-002 | 通过 | 单测 prompt 含旧摘要 + 400 字截断用例；eval-merge 真实 LLM 滚动合并（旧事实"年糕" + 新事实"PostgreSQL"双保留） |
| FR-003 | 通过 | `AgentServiceTest`：有摘要时 `spec.system` 含摘要段；E2E：原文已删情况下凭摘要答出暗号"42 台" |
| FR-004 | 通过 | 单测 llmThrows / blankOutput 两路径：不删消息、不改摘要 |
| FR-005 | 通过 | 单测断言 `LlmOptions.feature() == MEMORY_COMPRESS`；E2E 落 `llm_call_log` |
| FR-006 | 通过 | `ConversationCompressionEval` 真实 key 跑过：关键词 3/3 命中、长度 ≤400、合并去重正确 |

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-19 | 表结构从"改 V1__init.sql + 重建本地库"改为 V2 增量迁移（V1 恢复原样） | 用户要求保留本地数据；V1 恢复后 checksum 匹配，V2 随应用/测试启动自动应用。AGENTS.md 迁移约定同步放宽 |
