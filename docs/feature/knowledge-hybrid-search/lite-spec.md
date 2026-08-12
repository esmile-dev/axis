---
status: approved       # draft → approved → verified
feature: knowledge-hybrid-search
created: 2026-08-12
---

# 精简规格：知识库混合检索（向量 + 关键词 RRF 融合 + 可选 rerank）（M 级）

## 1. 需求

背景：当前 `VectorKnowledgeSearchService` 是裸向量检索（topK=5、无阈值、无过滤），纯向量检索对"精确关键词"（如报错码、专有名词）和"语义改写"难以兼得。业界标准解法：向量 + 关键词双泳道 + RRF 融合 + 相似度阈值 + 可选 rerank。本次只改检索层，`KnowledgeSearchService.search(String)` 签名不变，KnowledgeTool 等调用方零改动。

| 编号 | FR | 验收标准（当…时，系统应…） |
|------|-----|---------------------------|
| FR-001 | 混合检索：向量泳道（分块级 → 按条目聚合）与关键词泳道（**分词 LIKE**：英文/数字整词 + 中文 bigram，按命中 token 数打分）各取 top-10，按 RRF（k=60）融合输出 top-5；snippet 优先取向量命中块 | 单测：构造两泳道有序结果，验证融合排序与去重正确；接口签名不变 |
| FR-002 | 向量泳道加 `similarityThreshold`（yml 可配，默认 0.2），低质命中被过滤 | 单测：低于阈值的 Document 不进入结果 |
| FR-003 | 降级链不变且扩展：store 为 null 或向量泳道异常 → 返回关键词泳道 top-5（现行为）；rerank 失败/超时 → 保持 RRF 顺序 | 单测：三种降级路径正确 |
| FR-004 | 可选 LLM rerank：开关 `app.knowledge.search.rerank.enabled`（默认 false）；开启时对 RRF 后 top-10 用 ChatClient 重排，输出序号列表解析应用 | 单测：开启时按 LLM 输出重排；解析失败保持原序 |
| FR-005 | 可评测（§7.1）：新增 `RetrievalEval` + 评测集 `retrieval-golden.json`（合成条目 + 20 条 query），真实 PG + key 下对比"仅向量"与"hybrid"的 hit@5 | hybrid hit@5 ≥ 0.80 且 ≥ 仅向量基线；无 key/PG 时 assumeTrue 跳过不红 CI |

**范围外**：zhparser/pg_trgm 中文全文检索（LIKE 泳道已够用，零新依赖）；query 改写；父子块；citation/两段式问答（下一个 feature）；rerank 默认开启（由本次评测数字决定，另行 S 级改动）。

## 2. 方案要点

- 新增 `HybridKnowledgeSearchService implements KnowledgeSearchService`：组合"纯向量泳道"（从 `VectorKnowledgeSearchService` 抽取的包私有 ranked-hits 方法，不再内部降级）+ `KeywordKnowledgeSearchService`；RRF `score(item) = Σ 1/(60 + rank)`；融合后取 top-5。
- 装配改 `KnowledgeSearchConfig` 一处；`KnowledgeSearchHit` record 不变。
- 配置项收敛到 `app.knowledge.search.*`：`vector-top-k`(10) / `keyword-top-k`(10) / `similarity-threshold`(0.2) / `rerank.enabled`(false)。默认值评测后校准。
- rerank 复用 `AiConfigService.get()` 的 ChatClient，prompt 要求仅输出序号 JSON 数组，手解析（输出极简，不引 structured output）；30s 超时沿用 digest 风格。
- 评测：合成条目用固定 id 前缀入库 → reindex → 跑两模式 → 结束后清理（条目 + 向量）；评测集沿用 docs/feature/<功能>/evals → testResources 映射约定（pom 加一行映射）。
- 无表结构/接口/新依赖变更。

## 3. 任务

| ✓ | 任务 | 验收点 | Commit |
|---|------|--------|--------|
| ☑ | `VectorKnowledgeSearchService` 抽取纯向量泳道方法（ranked hits，不降级） | 现有单测仍绿 ✅ | 71657c4 |
| ☑ | `HybridKnowledgeSearchService`：RRF 融合 + 阈值 + 降级；装配切换 | 单测：融合/去重/阈值/降级 ✅ | 71657c4 |
| ☑ | LLM rerank（开关默认关）+ 解析失败兜底 | 单测：重排/解析失败两路径 ✅ | 71657c4 |
| ☑ | 评测集 `retrieval-golden.json`（10 合成条目 + 24 query）+ pom 映射 | 文件就位 ✅ | 71657c4 |
| ☑ | `RetrievalEval`：两模式 hit@1/3/5 对比 + 阈值断言 + 清理 | 真实环境跑出对比数字，达 FR-005 ✅ | 71657c4 |
| ☑ | 关键词泳道分词优化（评测驱动，见变更记录） | 单测 + 复跑评测数字分化 ✅ | 71657c4 |
| ☑ | 全量 `mvn test` | 187 全绿 ✅ | 71657c4 |

## 4. 验收记录

| FR | 结果 | 验证方式 |
|----|------|----------|
| FR-001 | 通过 | `HybridKnowledgeSearchServiceTest`：双路命中排最前/ snippet 取向量块/ limit 正确；`KeywordKnowledgeSearchServiceTest` 分词与打分用例 |
| FR-002 | 通过 | `VectorKnowledgeSearchServiceTest.search_topKAndThresholdPassedToRequest` 断言 SearchRequest 携带阈值 |
| FR-003 | 通过 | `HybridKnowledgeSearchServiceTest`：向量泳道异常 → 关键词 top-5；store null → 等于关键词序；`LlmKnowledgeRerankerTest` 失败保持原序 |
| FR-004 | 通过 | `LlmKnowledgeRerankerTest` 6 用例（重排/围栏容错/越界去重/解析失败/异常/单候选跳过）；开关默认 `false`（application.yml） |
| FR-005 | 通过 | `RetrievalEval`（真实 PG + 智谱 embedding-3，24 条 golden）：**hit@1 向量 0.79 → 混合 0.96；hit@3 0.96 → 1.00；hit@5 0.96 → 1.00**；无 key/PG 时跳过 |

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-12 | 关键词泳道由"整串 LIKE"改为分词匹配（英文/数字整词 + 中文 bigram，按命中不同 token 数打分）；repository 新增 `searchByToken` | 首轮评测两路打平（hit@1 均 0.79）：整串 LIKE 对自然语言查询零贡献，唯一双 MISS 的 `"1:15 是什么的参数"` 暴露根因。修复后 hit@1 0.79→0.96，该 query MISS→HIT。已经用户确认 |
| 2026-08-12 | `RetrievalEval` 的 embedding 配置解析镜像 `AiConfigService`：DB 激活 EMBEDDING 档案（`Encryptors.delux` 解密）优先，env 兜底 | env 的 `AI_BASE_URL` 指向 DeepSeek（无 embeddings 端点），按旧 env 路线必 404；应用实际走的是 DB 档案（智谱 embedding-3），评测应与之一致 |
| 2026-08-12 | rerank 维持默认关闭的最终决策 | 评测数字支持：融合后 hit@3/hit@5 已达 1.00，rerank 收益空间不足，且每次检索多一次 LLM 往返。数据驱动而非拍脑袋 |

## 5. 总结（2026-08-12）

**做了什么**：知识库检索从"裸向量"升级为混合检索——向量泳道（pgvector，分块级聚合）+ 关键词泳道（分词 LIKE）各取 top-10，RRF（k=60）按名次融合，可选 LLM rerank 精排（默认关），输出 top-5。`KnowledgeSearchService.search` 签名不变，调用方零改动；无新表、无新依赖。

**结果**（RetrievalEval，真实 PG + 智谱 embedding-3，24 条 golden query）：

| 指标 | 仅向量 | 混合 |
|------|--------|------|
| hit@1 | 0.79 | **0.96** |
| hit@3 | 0.96 | **1.00** |
| hit@5 | 0.96 | **1.00** |

**关键决策**：
1. 关键词泳道不上 zhparser/pg_trgm——零新依赖，分词（英文整词 + 中文 bigram）在 Java 侧做，对本项目语料够用。
2. rerank 实现但默认关——融合后 hit@3/5 已到 1.00，收益不足以抵消每次检索一次额外 LLM 往返。开关保留，语料变大后可复评。
3. RRF 只融合名次不融合分数——两泳道分数尺度不可比，名次融合免归一化、抗离群。

**经验**：
1. **评测先行暴露缺陷**：首轮评测两路打平（hit@1 均 0.79），定位出关键词泳道整串 LIKE 对自然语言查询零贡献；分词修复后 hit@1 +17pp。没有评测集这个问题不可见。
2. **评测环境必须镜像生产配置**：env 兜底的 DeepSeek 端点无 embeddings 能力（404），改为 DB EMBEDDING 档案解密优先后才测到真实链路。
3. 降级链保持完整：store null / 向量异常 / rerank 失败均有兜底，任何 AI 环节挂掉检索不空转。

---

# 附录：技术背景科普（学习材料，审阅可选）

> 以下与本功能的决策无关，纯知识点。按"面试能讲出来"的标准编写。

## A. 为什么纯向量检索不够

向量检索（语义检索）把文本编码成 embedding，按向量相似度找"意思相近"的内容：

- **擅长**：语义泛化。比如 query "怎么让搜索结果更准"，能命中写的是"检索质量优化方法"的文档——措辞完全不同也能匹配。
- **短板**：精确 token 匹配弱。报错码（`ORA-01555`）、版本号（`Spring Boot 4.0.7`）、专有名词、缩写这类 token 在 embedding 里语义信号很弱，会被"意思相近但实体不对"的结果挤掉。

关键词检索（LIKE / BM25）恰好相反：精确 token 强、语义泛化弱。两者互补——所以**混合检索（hybrid search）是业界标配**：Elasticsearch/OpenSearch 8.x+、Pinecone、Weaviate、Azure AI Search 全都内置了 hybrid 模式。一句话记忆：**向量管"意思"，关键词管"字眼"**。

## B. RRF（Reciprocal Rank Fusion，倒数名次融合）

多路检索结果融合的经典算法，公式：

```
score(d) = Σ 每一路的 1 / (k + rank)
```

- `rank` 是文档在该路结果里的名次（从 1 开始）；`k` 是平滑常数，本项目用 **k=60**。
- **为什么不用两路原始分数直接相加**：两路分数尺度不可比——向量路是 cosine similarity ∈ [0,1]，关键词路（LIKE）压根没有分数。要做加和就得先归一化，引入新超参还脆弱。RRF **只用名次、丢弃分数**，天然免归一化，对某一路的离群高分也不敏感（鲁棒）。
- **k 的作用**：k 越小，头名优势越大（rank 1 得 1/(k+1)，与 rank 10 拉开差距）；k 越大排名越"平"。k=60 来自原论文在 TREC 数据集上的经验值（Cormack et al., SIGIR 2009），Elasticsearch 的 RRF 实现默认也是 60——面试被问"k 怎么定的"，答"沿用论文与业界默认值，评测集验证过效果"即可。
- 没有命中某一路的文档，那一路不贡献分数（不是按 rank=∞ 惩罚）——所以"只在一路出现但名次高"的文档仍有竞争力，"两路都中"的文档自然浮到最前。

## C. 相似度阈值（similarityThreshold）

- pgvector 默认用 cosine distance；Spring AI 的 `SearchRequest.similarityThreshold` 过滤的是 **similarity = 1 − distance**，范围 [0,1]，越大越相似。
- 为什么需要阈值：向量检索是"矮子里拔将军"——**哪怕全库没有相关内容，topK 也一定会返回 k 个结果**。embedding 空间里无关内容也常拿到 0.1~0.25 的相似度，不设阈值这些"看起来像"的长尾就会混进结果。
- 经验分布（text-embedding-3-small 类模型）：真正相关通常 0.3~0.6；0.2 以下基本可视为噪声。所以默认 0.2 是宽松起点——宁可放过、不可错杀，精确值由本次 RetrievalEval 的评测数字校准。

## D. Rerank（双阶段检索架构）

生产级检索几乎都是两段式：

```
全库 ──一阶段 retrieve──▶ top-N 候选 ──二阶段 rerank──▶ top-k 结果
      （便宜、快、粗）              （贵、慢、准）
```

- 一阶段用便宜的信号（向量内积、倒排索引）从海量数据里快速筛候选；二阶段用更强的模型只看这几十个候选，精细排序。
- **reranker 两个流派**：
  - **Cross-encoder 模型**：query 和每个候选拼成一对，过一遍模型打出相关度分（如 bge-reranker、Cohere Rerank API）。准，但要引入新模型/新服务。
  - **LLM rerank**：把候选列表连同 query 丢给现有 LLM，让它直接输出排序后的序号。效果略逊于专用 reranker，但**零新依赖**。
- 本项目选 LLM rerank：复用 `AiConfigService` 的 ChatClient，候选只有 top-10，一次调用成本可控。代价是每次检索多一次 LLM 往返（百毫秒~秒级延迟）——所以做成开关默认关，**让评测数字决定是否该默认开**，而不是拍脑袋。

## E. 评测指标 hit@k

- **hit@5**：每条 query 标注的"期望条目"是否出现在结果前 5 名，取所有 query 的比例。直白、好算，适合我们"每条 query 标 1 个期望条目"的小评测集。
- 进阶指标（知道即可，面试可能追问）：**MRR**（Mean Reciprocal Rank，第一个正确结果名次的倒数取均值——正确答案排第 1 得 1 分、排第 2 得 1/2）、**nDCG**（考虑完整名次分布的加权指标，适合一条 query 有多个相关文档且分级别的场景）。
- 评测的意义不止一个数字：它是"改动是否有效"的**可重复判决器**——之后调 chunk 策略、换 embedding 模型、开 rerank，都用同一套数字说话。这就是 workflow §7.1"AI 功能验收必须可评测"的用意。

