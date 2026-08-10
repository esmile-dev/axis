---
status: verified       # draft → approved → verified
feature: knowledge-chunking
created: 2026-08-06
---

# 精简规格：知识库分块优化（M 级）

## 1. 需求

现行 `KnowledgeChunker` 定长滑窗（1000 字符/重叠 100）在 golden 评测集（`knowledge-2.0/evals/summary-golden.jsonl` 10 篇真实抓取文章）实测：83 个内部边界中 80 个（96%）断句；中文语料 11–35% 字符为图片 `![](url)` 噪声进向量，5 块（≈5%）剥掉噪声后实义不足 100 字符；标题只在 metadata 不进向量。本地库实测 4 篇/16 向量——千块级以下规模，语义切分/LLM contextual 等重度方案明确推迟。原 `docs/spike/knowledge-chunker.md` 已并入本文并删除。

| 编号 | FR | 验收标准（当…时，系统应…） |
|------|-----|---------------------------|
| FR-001 | 递归结构切分 | 当条目内容入库时，块按 `\n\n` → `\n` → 句末标点 → 硬切兜底递归切分；每块 ≤500 token（CL100K，jtokkit 计量）；重叠取前块尾部整句（≤50 token）；边界不再切断句子 |
| FR-002 | 嵌入文本清洗 | 当生成嵌入文本时，图片语法、`{#anchor}` 残留、HTML 注释被去除，`[text](url)` 收敛为 `text`，连续空白折叠；DB 中原文不变 |
| FR-003 | 标题前置 | 当生成嵌入文本时，每块文本 = 标题 + 块内容；标题为空则省略 |
| FR-004 | 全量重建入口 | 当 POST `/api/knowledge/reindex` 被调用时，遍历全部条目幂等重建向量（复用 `indexItem`），返回处理条数 |

**范围外**：小块合并、heading path 进 metadata、snippet 句边界化（P1）；hybrid/语义切分/父子块（P2）；PATCH 改 content 不触发重建的既有缺口（另记待办）。

## 2. 方案要点

`KnowledgeChunker.chunk(title, content)` 仍为纯函数：清洗 → 递归切 → 标题前置，一步产出嵌入文本；`VectorKnowledgeIndexService` 仅改调用签名。token 计量用 jtokkit（spring-ai-commons compile 依赖，零新增）。无表结构、无接口契约（KnowledgeTool）、无 prompt 变更。

为何不用 Spring AI 2.0 现成组件（已核实 `spring-ai-commons-2.0.0` 源码）：`TokenTextSplitter` 无 overlap、默认标点仅 `.?!` 无中文、无清洗；`MarkdownDocumentReader` 输入要求 `Resource`（语料在 DB）、标题进 metadata 不进向量、需新增 commonmark 依赖（项目已有 flexmark）；1.x 的 LLM metadata enricher 已在 2.0 移除。框架只有积木没有方案，P0 必须自研。

评测（workflow §7 可评测条款）：

- **结构指标**（离线脚本对 `knowledge-2.0/evals/summary-golden.jsonl` 10 篇跑新算法）：坏边界率 ≤5%（基线 96%）；近噪声块 = 0（基线 5 块）
- **检索评测**：`KnowledgeVectorSearchEval` 回归通过；新增 1 条图文混排长文样例 + 针对文中部细节的语义查询，断言目标条目排第一（旧算法预期不通过/不稳，新算法通过，构成 A/B 对照）

## 3. 任务

| ✓ | 任务 | 验收点 | Commit |
|---|------|--------|--------|
| ☑ | T-001 重写 `KnowledgeChunkerTest`：新契约全红 | 边界/重叠/清洗/标题/硬切/空值 11 用例就绪 | 待提交 |
| ☑ | T-002 重写 `KnowledgeChunker` + 接线 `VectorKnowledgeIndexService` | 新测试全绿；`mvn test -pl axis-service` 169/169 | 待提交 |
| ☑ | T-003 `POST /api/knowledge/reindex` + 本地 4 篇全量重建 | 返回 `{"reindexed":4}`；vector_store 33 块/3 条目（1 篇纯图片页清洗后正确地不落向量）；抽查块首=标题、零 `![`/`{#` 残留 | 待提交 |
| ☑ | T-004 评测：结构指标 `KnowledgeChunkerGoldenTest` + `KnowledgeVectorSearchEval` 增强（图文样例+细节查询） | 坏边界率 1.2%（阈值 ≤5%，基线 96%）、近噪声块 0（基线 5）；eval 2/2 通过 | 待提交 |
| ☑ | T-005 文档：09 架构图与参数同步、本 spec 验收记录 | 文档与代码同提交 | 待提交 |

## 4. 验收记录

| FR | 结果 | 验证方式 |
|----|------|----------|
| FR-001 | ✓ | `KnowledgeChunkerTest` 11 用例 + `KnowledgeChunkerGoldenTest`：10 篇 golden 文章 94 块，84 个边界坏 1 个（1.2%，阈值 ≤5%，旧算法基线 96%）；块均 ≤500 token（jtokkit CL100K） |
| FR-002 | ✓ | golden 集断言零 `![`/`{#`/`<!--` 残留、零近噪声块（基线 5 块）；重建后 vector_store 抽查：仅代码块内合法 URL 保留；纯图片条目（智谱页）清洗后为空正确地不落向量 |
| FR-003 | ✓ | 重建后 33 个向量块均以 `标题\n\n` 开头（psql 抽查）；单测 `chunk_shortText_singleChunkWithTitlePrefix` / `chunk_blankTitle_noPrefix` |
| FR-004 | ✓ | 临时端口 7799 新 jar 实测：`POST /api/knowledge/reindex` 返回 `{"reindexed":4}`，2.7s 完成；`KnowledgeServiceTest.reindexAll_rebuildsEveryItem` |
| 检索评测 | ✓（A/B 说明） | `KnowledgeVectorSearchEval` 2/2 通过（智谱 embedding-3，修正 eval 缺 `dimensions=1536` 的 harness 问题）。旧算法 worktree 基线同跑 2/2——小语料下 top-1 排序不区分，差异体现在 snippet 质量：旧算法对图文样例返回的 snippet 是图片行 `![](...banner.png)`，新算法返回标题+正文 |

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-06 | spike `knowledge-chunker` 转正：实测基线与 Spring AI 选型论证并入 §1/§2，原 spike 删除；status → approved | 用户确认 P0 开工 |
| 2026-08-06 | 全部任务完成，验收记录回填；status → verified | T-001~T-005 验收通过，待随代码提交 |
