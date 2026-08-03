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
| ☐ | T-009 | 详情三视图：原文渲染/总结/markmap 脑图（含大纲切换、骨架屏、失败重试） | FR-006 | 三 tab 正常；脑图可交互；失败态可重试 | |
| ☐ | T-010 | 状态切换 + 滚动进度自动记录与恢复 + 标签编辑 + Inbox「转入知识库」入口 | FR-007/008 | 状态/进度持久化；Inbox 转入全链路通 | |
| ☐ | T-011 | P2 问答：`/api/knowledge/{id}/chat` SSE + 详情页问答面板 + qa-golden 评测 | FR-009, NFR-005 | 流式问答可用、历史持久化；评测达标 | |
| ☐ | T-012 | P2 检索：pgvector 检测装配 + 分块 embedding + KnowledgeTool 重写 + 降级 | FR-010 | 语义检索命中；禁 embedding 降级可用有 WARN | |

## 验收记录（实现完成后填写，G2 用）

| 对应 FR | 结果 ✓/✗ | 验证方式（命令 / 请求响应 / 操作步骤） |
|---------|----------|----------------------------------------|
| NFR-003/004（T-006） | ✓ | 见下方 AI 评测结果 |

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
