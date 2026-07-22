---
status: approved       # G3 用户提前确认 2026-07-22
feature: digest-2.0
created: 2026-07-22
---

# 任务清单：digest-2.0

## 执行规则（AI 必读）

- 一次只做一个任务；完成 → 打勾 → 填 commit hash → 下一个
- 不做任务表之外的事（想顺手优化 → 提建议，不直接做）
- 验收点不通过 → 修到过为止；需要偏离已批准设计 → 停下，走 design.md 变更流程

## 任务表

| ✓ | 编号 | 任务内容 | 对应 FR | 验收点（怎么算完成） | Commit |
|---|------|----------|---------|----------------------|--------|
| ☑ | T-001 | 新增 2 个 JPA 实体 + repository：`AppConfig`、`ArticleSummaryCache`；`DigestExecutionLog.llmCallCount` 字段 | FR-001/002、NFR-003 | `mvn -pl axis-service compile` 过；`psql \dt` 看到 2 张新表；`\d digest_execution_log` 看到 `llm_call_count` 字段 | ca4f5bb |
| ☑ | T-002 | 实现 `AiConfigService`：启动 `@PostConstruct` 加载（DB 优先→env 兜底）；`Encryptors.delux` 加密 API key；`volatile ChatClient currentClient`；`reload()` 原子替换；`get()` 返回当前实例 | FR-001、NFR-002 | 单测覆盖：DB 有→用 DB；DB 空→用 env；reload 后 `get()` 返回新实例；DB dump 不可见明文 key | a74e689 |
| ☑ | T-003 | 实现 `ConfigController` 4 端点（GET/PUT/POST reload/POST test，`/api/v1/config/ai*`），API key 出入均加掩码 | FR-001/004 | curl 4 个端点全部 200；GET 返回 apiKey=`***`；test 端点用错误 key 返回失败 JSON | 6a2e745 |
| ☑ | T-004 | 重构 `AiConfig.java`：删除 `chatClient` `@Bean`；chat agent 改为注入 `AiConfigService.get()` | FR-001 | `mvn compile` 过；`/api/agent/chat` 端到端通（用真实 key） | be2b0c2 |
| ☑ | T-005 | 实现 `SummarizationService`：`summarize(article)` 精读（30s timeout + JSON 解析失败重试 1 次）；`editor(sectionedArticles)` 主编；按 `link` 读/写 `ArticleSummaryCache` | FR-002/003、NFR-001 | 单测：mock ChatClient 成功/超时/坏 JSON 三场景；超时回退到 description | 7e1c2eb |
| ☑ | T-006 | 改造 `DailyDigestService`：注入 `SummarizationService` + `AiConfigService`；`curateBySection` 拆为 `curateBySectionGrouped` 返回 `Map<Category, List<Article>>` 保留顺序；串联 cache 查→精读→主编→降级→写 inbox；记录 `llmCallCount` | FR-002/003、NFR-003 | 配错 API key 触发 digest 仍 COMPLETED 且 summary 含"AI 摘要暂不可用"；同日 2 次 digest 第 2 次 LLM 调用 = 1 | b5163c0 |
| ☑ | T-007 | 改造 `settings.vue`：写后端（`$fetch` PUT）；测连按钮（POST test）；来源显示（GET 返回 source 字段）；立即生效按钮（POST reload） | FR-001/004 | UI 填值→保存→刷新仍在；点击测连→toast 反馈；切到错误 key→点立即生效→下次 digest 走降级 | 3731277 |
| ☑ | T-008 | 新增 `docs/feature/digest-2.0/evals/summarize-golden.jsonl`（20 条真实 RSS）；新增 `evals/editor-judge.md`（5 分制评分准则） | FR-002/003、NFR-004 | 文件存在；20 条可解析；judge.md 含明确评分维度 | 6d0f7ad |
| ☐ | T-009 | 新增 `src/test/java/.../digest/summarize/SummarizationEval.java`：跑 20 条 golden case，断言字段填充率 100% + `why_it_matters` 长度 [20,100] 合格率 ≥ 85% + `headline` 长度 [5,20] 合格率 ≥ 90% | NFR-004 | `mvn test -pl axis-service -Dtest=SummarizationEval` 全绿 | |
| ☐ | T-010 | 端到端验证：起后端 + 触发 digest + 看 Inbox + 验证 `llmCallCount` + 手动走一次降级路径；写 `summary.md` 复盘 | 全部 | Inbox 出现新条目含 `why_it_matters`；配错 key 走降级；10 个 commit | |

## 验收记录（实现完成后填写，G4 用）

| 对应 FR | 结果 ✓/✗ | 验证方式（命令 / 请求响应 / 操作步骤） |
|---------|----------|----------------------------------------|
| FR-001 | | |
| FR-002 | | |
| FR-003 | | |
| FR-004 | | |
| NFR-001 | | |
| NFR-002 | | |
| NFR-003 | | |
| NFR-004 | | |

## 评测结果

（实现后填写）
- 评测集版本：
- 精读：字段填充率 / `why_it_matters` 长度合格率 / `headline` 长度合格率
- 主编：开场白均分 / 各版导语均分
- 失败样例：
