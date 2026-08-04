---
status: approved       # G1 通过 2026-08-03
feature: knowledge-2.0
created: 2026-08-03
---

# 任务清单：知识库 2.0

## 执行规则（AI 必读）

- 一次只做一个任务；完成 → 打勾 → 填 commit hash → 下一个
- 不做任务表之外的事（想顺手优化 → 提建议，不直接做）
- 验收点不通过 → 修到过为止；需要偏离已批准设计 → 停下，走 design.md 变更流程

## 任务表

| ✓ | 编号 | 任务内容 | 对应 FR | 验收点（怎么算完成） | Commit |
|---|------|----------|---------|----------------------|--------|
| ☑ | T-001 | 数据模型：`knowledge/` 包实体（KnowledgeItem/KnowledgeArtifact/枚举/tag）+ Repository；删除旧 Knowledge 四件套与旧表说明；KnowledgeTool 临时适配新模型（保证全模块编译通过，向量升级留 T-012） | FR-001 | `mvn compile` 过；启动后新表建成；旧类不再存在 | fc08813 |
| ☑ | T-002 | 基础 API：粘贴创建/列表筛选搜索/详情/PATCH/DELETE（DTO record + Bean Validation）+ Service 单测 | FR-001/005 | curl 全链路通；`mvn test -pl axis-service` 过 | 9c059f0 |
| ☑ | T-003 | URL 抓取管线（jsoup+flexmark，15s 超时，422 错误语义）+ 单测 | FR-002 | 真实 URL 抓取 content 干净；故障注入返回 422 | 6300732 |
| ☑ | T-004 | 文件导入（md/txt/pdf，pdfbox，≤20MB，存 UPLOAD_DIR/knowledge/）+ 单测 | FR-003 | 三类文件导入成功；超限/类型错误被拒 | 371c42f |
| ☑ | T-005 | 异步生成管线：事件+@Async+两产物状态机+regenerate 端点+总结/脑图 prompt 初版 | FR-004, NFR-001 | 入库后产物自动 DONE；注入 LLM 故障 → FAILED → regenerate 恢复 | 4ce2729 |
| ☑ | T-006 | 评测：`evals/summary-golden.jsonl`（≥10 篇）+ 脑图合法性校验 + 评测入口，跑出达标结果 | NFR-003/004 | 评测实测达阈值，结果填入验收记录 | cf1b1fd |
| ☑ | T-007 | from-inbox 端点：URL/文字两分支 + inbox 标已读 + 单测 | FR-008 | 两类条目转入成功且原条目 readAt 非空 | 0004913 |
| ☑ | T-008 | 前端骨架：`pages/knowledge/` 三栏布局 + 左导航筛选 + 列表 + 添加 Dialog（三 tab）+ ⌘K 命令 | FR-001/002/003/005 | 页面可用，筛选搜索添加全通；旧 knowledge.vue 删除 | 3681752 |
| ☑ | T-009 | 详情三视图：原文渲染/总结/markmap 脑图（含大纲切换、骨架屏、失败重试） | FR-006 | 三 tab 正常；脑图可交互；失败态可重试 | 3083179 |
| ☑ | T-010 | 状态切换 + 滚动进度自动记录与恢复 + 标签编辑 + Inbox「转入知识库」入口 | FR-007/008 | 状态/进度持久化；Inbox 转入全链路通 | a16b922 |
| ☑ | T-011 | P2 问答：`/api/knowledge/{id}/chat` SSE + 详情页问答面板 + qa-golden 评测 | FR-009, NFR-005 | 流式问答可用、历史持久化；评测达标 | 8b65bd7 |
| ☑ | T-012 | P2 检索：pgvector 检测装配 + 分块 embedding + KnowledgeTool 重写 + 降级 | FR-010 | 语义检索命中；禁 embedding 降级可用有 WARN | 98cb021 |

## 后续任务（backlog，G2 后另立项）

- **B-001** 会话列表端点按前缀过滤：`knowledge-*` 会话当前会出现在 /chat 页侧栏（`JpaChatMemoryRepository` 对任意 conversationId upsert `chat_conversation`），且从主聊天页向该会话发消息会双向污染条目问答上下文，侧栏删除也会误删条目问答历史。修法：`ChatHistoryService.listConversations()` 过滤 `knowledge-%` 前缀（或给会话加来源标记），并考虑禁止主聊天页写入 `knowledge-*` 会话。注意 `ChatHistoryService.java` 当前有用户未提交改动，需等其落地后实施。（T-011 审查发现）
- **B-002** `KnowledgeTool.createDocument` 直存仓储不发事件：Agent 聊天中创建的知识文档不进异步加工管线（产物永远 PENDING）也不进向量索引，与 design.md §6「走新创建管线（type=NOTE，触发异步加工）」有偏差（T-001 临时态遗留）。修法：改调 `KnowledgeService.create`（自动带事件）。（T-012 审查发现）
- **B-003** pgvector 正向语义路径环境阻塞：本机 PG16 无 vector 扩展（未获授权安装系统软件）。安装后运行 `mvn test -pl axis-service -Dtest=KnowledgeVectorSearchEval` 复验 FR-010 正向路径。（T-012 环境记录）

## 验收记录（实现完成后填写，G2 用）

| 对应 FR | 结果 ✓/✗ | 验证方式（命令 / 请求响应 / 操作步骤） |
|---------|----------|----------------------------------------|
| NFR-003/004（T-006） | ✓ | 见下方 AI 评测结果 |
| FR-009/NFR-005（T-011） | ✓ | 临时端口新 jar（7799）curl 实测：SSE token/done 帧流式输出且答案源自条目、追问代词可解析、超纲答「原文未提及」、两条目互不串、`GET /api/agent/conversations/knowledge-{id}/messages` 历史持久化、不存在条目 404；评测见下方 |
| FR-010（T-012） | ✓（降级路径实测；正向语义路径**环境阻塞**——本机 PG 无 pgvector 扩展，按简报未装系统软件，装扩展后跑 `KnowledgeVectorSearchEval` 复验） | `mvn test -pl axis-service` 155/155（含分块/降级/探测/运行时降级单测）；临时端口 7799 新 jar 实测：启动日志 `WARN knowledge.vector.degraded reason=pgvector-extension-missing`，创建条目后 Agent chat 触发 `搜索知识库` 工具帧并关键词命中目标条目，清理无残留 |

<!-- AI/Agent 功能追加评测结果：评测集版本 / 指标 / 阈值 / 实测 / 失败样例 -->

### T-006 AI 评测结果（2026-08-04）

- **评测集**：`docs/feature/knowledge-2.0/evals/summary-golden.jsonl` v1（2026-08-04 定稿入库）——10 篇真实文章（6 中 4 英，技术博客/教程/资讯，正文 1.0k–25k 字符），T-003 真实管线抓取整理
- **评测入口**：`KnowledgeSummaryEval`（JUnit，`mvn test -pl axis-service` 随全量触达；无 `AI_API_KEY` 自动跳过）。触发命令：`set -a; source .env; set +a; mvn test -pl axis-service -Dtest=KnowledgeSummaryEval`（模型 deepseek-v4-flash，judge 契约见 `evals/summary-judge.md`）
- **实测汇总输出原文**（最终全量轮，BUILD SUCCESS）：

```
[zh-01] structure=OK relevance=5 mindmap=OK reason=总结完全忠实原文，准确覆盖 Dario 观点与作者反驳，结构完整，无编造或遗漏。
[zh-02] structure=OK relevance=5 mindmap=OK reason=总结完全忠实原文，准确覆盖四种缓存模式及关键观点，结构完整无编造。
[zh-03] structure=OK relevance=5 mindmap=OK reason=总结完全忠实原文，准确抓住反讽主旨与认知/知识/技能/领导力四大维度，结构完整且无编造。
[zh-04] structure=OK relevance=5 mindmap=OK reason=总结准确覆盖文章核心内容，三节结构完整，无遗漏或失实。
[zh-05] structure=OK relevance=5 mindmap=OK reason=总结完全忠实于原文，结构完整且准确覆盖交易系统演进、DDD实践与核心洞察，无编造或遗漏。
[zh-06] structure=OK relevance=5 mindmap=OK reason=总结完全忠实原文，准确覆盖核心主旨与关键内容，且TL;DR、要点、关键洞察三节结构完整，无可挑剔。
[en-01] structure=OK relevance=5 mindmap=OK reason=总结完全忠实原文，准确覆盖核心观点并包含 TL;DR、要点、关键洞察三节，无编造或遗漏。
[en-02] structure=OK relevance=5 mindmap=OK reason=总结完全忠实原文，涵盖核心观点与关键洞察，结构完整，无编造或遗漏。
[en-03] structure=OK relevance=5 mindmap=OK reason=总结完全忠实原文，涵盖所有核心要点，结构完整，无编造或遗漏。
[en-04] structure=OK relevance=5 mindmap=FAIL [H1 数量=0，应为 1, 节点总数 0，不在 5-40 区间] mindmapError=OpenAIInvalidDataException: Error reading response reason=总结完全忠实于原文，准确覆盖超线性回报的两种成因、核心启发与关键领域，三节结构完整，无编造或明显偏差。
KnowledgeSummaryEval: total=10 summaryStructure=10/10 (1.00, 阈值 1.00) relevance>=4=10/10 (1.00, 阈值 0.80) mindmapLegal=9/10 (0.90, 阈值 0.90)
```

| 指标 | 阈值 | 实测 | 结论 |
|------|------|------|------|
| 总结结构合格率（脚本） | 100% | 10/10 = 100% | ✓ |
| judge 相关性 ≥4/5 占比 | ≥80% | 10/10 = 100%（全部 5 分） | ✓ |
| 脑图合法性合格率（脚本） | ≥90% | 9/10 = 90% | ✓ |

- **失败样例**：en-04《Superlinear Returns》（25k 字符长文）脑图生成 LLM 调用抛 `OpenAIInvalidDataException`（transient API 读错误），产物按设计标 FAILED、内容为空——属 API 抖动而非 prompt 质量问题（该文脑图在迭代验证轮产出过合法大纲）；生产语义正确（可 regenerate 恢复）
- **prompt 迭代记录**：第 1 轮脑图 6/9（长文节点 55/60 超标、超短文零标题）→ 强化 `PROMPT_MINDMAP` 节点数约束；第 2 轮结构 9/10（en-02 缺「关键洞察」节）→ `PROMPT_SUMMARY` 补「三节缺一不可」。两轮均记入 design.md 变更记录

### T-011 AI 评测结果（2026-08-04）

- **评测集**：`docs/feature/knowledge-2.0/evals/qa-golden.jsonl` v1（2026-08-04 定稿入库）——6 条 QA（基于 summary-golden 文章：zh-01/zh-02×2/en-01/en-03 事实型 5 条 + zh-01 超纲拒答 1 条，keyPoints 为 judge 用参考答案要点）
- **评测入口**：`KnowledgeQaEval`（JUnit，`mvn test -pl axis-service` 随全量触达；无 `AI_API_KEY` 自动跳过）。触发命令：`export $(grep '^AI_' .env | xargs) && mvn test -pl axis-service -Dtest=KnowledgeQaEval`（模型 deepseek，judge 契约见 `evals/qa-judge.md`）
- **链路真实性**：每条 QA 走真实 `KnowledgeQaService`（system prompt 构建 + 全行共享一个 `MessageWindowChatMemory` 实例——conversationId 接线错误会让条目上下文互串、judge 判 FAIL，隔离性由此被真实验证）+ LLM-judge 二元判定（VERDICT PASS/FAIL，`QaJudgeParser` 解析并有单测自检）
- **实测汇总输出原文**（最终全量轮，两轮均 BUILD SUCCESS）：

```
[zh-01] verdict=PASS reason=答案完整覆盖了三个参考答案要点，且事实均源自原文。 | Q: Dario Amodei 为什么认为公开的 AI 模型不能叫「开源」？
[zh-02] verdict=PASS reason=答案准确复述了原文中Cache Aside的正确更新顺序及先删缓存再更新数据库导致脏数据的并发问题，覆盖全部要点。 | Q: Cache Aside 模式更新数据时的正确顺序是什么？为什么先删缓存再更新数据库是错的？
[zh-02] verdict=PASS reason=答案准确概括了Write Behind/Write Back的核心思路与代价，且覆盖全部参考答案要点，内容均源自原文。 | Q: Write Behind（Write Back）模式的核心思路是什么？代价是什么？
[en-01] verdict=PASS reason=答案准确摘取并覆盖了原文中关于两者差异的两个要点，且无编造或外部信息。 | Q: How does agentic programming differ from vibe coding?
[en-03] verdict=PASS reason=答案准确覆盖原文要点：shell 不负责通过 $PATH 查找命令，该工作由 exec 完成。 | Q: According to the article, which component is responsible for finding commands via the $PATH environment variable?
[zh-01] verdict=PASS reason=答案明确说明原文未提及向量数据库或 RAG 系统，且未编造任何推荐，符合超纲问题的覆盖要求。 | Q: 作者在文中推荐了哪款向量数据库来搭建 RAG 系统？
KnowledgeQaEval: total=6 pass=6/6 (1.00, 阈值 0.80)
```

| 指标 | 阈值 | 实测 | 结论 |
|------|------|------|------|
| judge 合格率（源自原文且覆盖 keyPoints） | ≥80% | 6/6 = 100%（连续两轮全量） | ✓ |
| 超纲拒答样例 | ≥1 条且计入通过率 | 1 条（zh-01 向量数据库推荐），两轮均 PASS | ✓ |

- **失败样例**：无
- **prompt 迭代记录**：未迭代——system prompt 初版（标题+总结+原文注入、「原文未提及」拒答、语言跟随问题）首轮即 6/6，复测一轮确认稳定
