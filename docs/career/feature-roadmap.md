# ·建议补充的 Feature 路线图（边学边做）

按面试性价比排序：P0 不做会被问穿、做了直接加分；P1 拉开与玩具项目的差距；P2 加分项。

每项结构：为什么值得做 → 学到什么 → 实现要点 → 工作量。完成后勾掉并补 commit hash。

---

## P0 —— 必做

### 1. RAG 检索深化：混合检索 + Rerank + 阈值（最高优先级）

- [x] 状态：已完成（2026-08-12，`docs/feature/knowledge-hybrid-search/lite-spec.md`；**hit@1 向量 0.79 → 混合 0.96，hit@3/5 → 1.00**（24 条 golden）；rerank 实现但评测数字支持默认关；评测驱动修复关键词泳道分词缺陷）

**为什么**：当前 RAG 是"裸向量检索"（topK=5、无阈值、无过滤）。面试官问 RAG 几乎必问："纯向量检索的局限是什么？怎么解决？"标准答案就是混合检索 + rerank，RAG 工程第一常识。

**学到什么**：BM25/全文检索原理、RRF（Reciprocal Rank Fusion）融合算法、rerank 模型（cross-encoder vs LLM rerank）、检索质量评测（召回率/命中率）。

**实现要点**（贴合现有代码）：
- `VectorKnowledgeSearchService` 旁加 PostgreSQL 全文检索（`to_tsvector` + `ts_rank`，中文可配 `zhparser` 或 `ILIKE` 兜底），两路结果 RRF 融合（`score = Σ 1/(k+rank)`，k=60）。
- 加 `similarityThreshold`（SearchRequest 原生支持）过滤低质命中。
- Rerank 先做 LLM rerank（候选块 + query 给 LLM 打分排序，零新依赖，只对 top 10~20 重排）；有余力再接 Cohere/Jina rerank API。
- **配套评测**：写一个小评测集（query → 期望命中的 item），对比改造前后命中率——有数字简历才能写"检索命中率从 X 提升到 Y"。

**工作量**：M 级，3~5 天。

### 2. 两段式 RAG 问答 + 引用来源（Citation）

- [x] 状态：已完成（2026-08-12，`docs/feature/knowledge-ask-rag/lite-spec.md`；`POST /api/knowledge/ask` 三帧协议 + "问知识库"面板；`KnowledgeAskEval` 10/10 通过，引用全中）

**为什么**：现在知识库 QA 是"单条目全文塞 prompt（10 万字符截断）"，跨条目只有 Agent 的 top-5 snippet（200 字符）——不是真 RAG。"检索 → 取全文 → 生成 + 标注引用来源"才是标准完整链路，citation 是 RAG 可信度的招牌特性。

**学到什么**：RAG 管线设计（retrieve/rerank/stuff/cite）、context window 预算分配、答案 grounding（防幻觉 prompt 约束 + 引用校验）。

**实现要点**：新增 `/api/knowledge/ask`：query → 混合检索 top-N 块 →（rerank）→ 按 token 预算装入 prompt（标注 `[1][2]` 编号 + item_id）→ LLM 生成带 `[n]` 引用的回答 → SSE 流式返回，前端把 `[n]` 渲染成可点击引用跳转条目。Prompt 硬约束"仅基于给定资料回答，无资料则明说"。

**工作量**：M 级，2~3 天。

### 3. Structured Output 替换手搓 JSON 解析

- [x] 状态：已完成（2026-08-10，`docs/feature/digest-structured-output/lite-spec.md` 已验收；eval 20 条 golden：why_it_matters 1.00 / headline 0.90；顺带修复 editor prompt 占位符失效与 eval JSONL 解析两个潜伏缺陷）

**为什么**：Digest 摘要目前靠字符串替换 + 剥 ``` 围栏解析 JSON，重试仅 1 次——脆弱。Spring AI 2.0 原生 structured output（`.entity()`，底层走 `response_format: json_schema`）。改造后可讲"从脆弱的正则解析迁移到 schema 约束的结构化输出"的真实演进故事。

**学到什么**：LLM 输出契约的三种保障层级（prompt 约束 → JSON mode → structured output）及各自可靠性。

**实现要点**：`SummarizationService` 逐篇精读和 editor pass 改为 `chatClient.prompt().user(...).call().entity(XxxOutput.class)`（record 定义字段）；保留一次重试兜底。

**工作量**：S 级，半天~1 天。

### 4. 危险操作的 Human-in-the-loop 确认

- [ ] 状态：未开始

**为什么**：AGENTS.md 自己写了"Agent 执行不可逆操作前默认需要人工确认节点"——但代码没实现。面试官问"Agent 乱删数据怎么办"（Agent 安全性几乎必问），现在只能答"没做"。实现了就是"规划-确认-执行"的 Agent 安全模式。

**学到什么**：Agent 安全模式（confirmation gate）、中断/恢复式工作流（SSE 流中间插入确认帧）、tool 权限分级。

**实现要点**：Tool 分级——`deleteIssue`/`deleteInboxItem` 等标记为 dangerous；Agent 调危险 tool 时不直接执行，SSE 发 `{"type":"confirm","action":...,"confirmId":...}` 帧挂起；前端弹确认框，用户确认后 `POST /api/agent/confirm/{id}` 才执行并继续生成。

**工作量**：M 级，3~4 天（要动 SSE 协议和前端，值得）。

---

## P1 —— 拉开差距

### 5. LLM 可观测性：调用日志 + Token 成本追踪

- [ ] 状态：未开始

**为什么**：生产级 LLM 应用必做。已有 `llm_call_count` 雏形，扩展成完整体系。面试官问"线上怎么知道 LLM 花了多少钱、慢在哪"，这是标准答案。

**实现要点**：每次 LLM 调用记录 model、prompt 类型、token 用量（prompt/completion tokens，从 `ChatResponse.getMetadata().getUsage()` 取，流式在最后一帧取）、耗时、成本估算，存 `llm_call_log` 表；Settings 页加用量面板；Micrometer 暴露指标。

**工作量**：S~M，1~2 天。

### 6. 上下文压缩（替代硬窗口截断）

- [ ] 状态：未开始

**为什么**：短期记忆 100 条硬截断会丢失早期关键信息。标准解法"窗口 + 摘要压缩"：超窗旧消息由 LLM 压缩成摘要放 system prompt。面试官问"长对话怎么处理上下文限制"的进阶答案（配合"为什么不用无限拉长 prompt"——成本、注意力稀释）。

**学到什么**：context window 管理策略谱系（截断/滑动窗口/摘要压缩/向量化检索历史）。

**工作量**：M，2~3 天。

### 7. SSE 流式健壮性：取消 + 错误帧 + 重试

- [ ] 状态：未开始

**为什么**：当前无 AbortController（无法停止生成）、无错误帧（LLM 挂了前端只能猜）、`aiExpand` 有 TextDecoder 跨 chunk 乱码隐患。**修 bug 的叙事比堆 feature 更打动资深面试官**——"我发现并修复了流式链路的 X 个问题"。

**实现要点**：前端加 AbortController + 停止按钮；协议加 `{"type":"error","message":...}` 帧（`onErrorResume` 映射）；`aiExpand` 的 decoder 改为复用 + `{stream:true}`。

**工作量**：S，1 天。

### 8. 向量与条目数据一致性

- [ ] 状态：未开始

**为什么**：`KnowledgeService.update()` 改标题不触发 reindex，向量库与 DB 漂移。**主动讲"我发现了一致性缺口并修复"比被问出来强十倍**。

**实现要点**：update 触发 `indexItem`（先删后写，复用现有幂等逻辑），或引入版本戳/事件驱动同步。

**工作量**：S，半天。

---

## P2 —— 加分项

### 9. MCP（Model Context Protocol）支持

- [ ] 状态：未开始

**为什么**：MCP 是 2025 年 Agent 生态最热标准，Spring AI 2.0 原生支持 MCP client/server。把五个领域 Tool 暴露为 MCP server，任何 MCP 兼容客户端都能操作 Axis——"紧跟 Agent 生态标准"的强信号。

**工作量**：M，2~3 天。

### 10. 评测体系完善：LLM-as-judge

- [ ] 状态：未开始

**为什么**：落地 `run-editor-judge.py`（现是 TBD）+ 给 RAG 问答加 LLM-as-judge 评测（相关性/忠实度/引用准确性三维打分）。分块器 golden test + judge 体系，"可评测"这条线闭环。

**工作量**：M，2~3 天。

### 11. Reindex 任务化

- [ ] 状态：未开始

**为什么**：同步阻塞遍历 → 异步任务 + 进度可见（processed/total）+ Settings 页触发按钮 + 与进行中索引的互斥锁。补掉"reindex 只能 curl"的短板。

**工作量**：S，1 天。
