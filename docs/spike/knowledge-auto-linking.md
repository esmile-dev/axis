---
name: knowledge-auto-linking
description: Knowledge Base 文档通过语义向量搜索自动建立关联，在文档侧边栏提示"隐性关联 (Related Docs: N)"。本 spike 收敛 embedding 模型与向量存储选型。
status: draft
---

# Spike: Knowledge Auto-Linking

## 1. 背景

用户每天积累多篇 Knowledge 笔记，但孤立条目之间没有显式关联，错过"这条报错笔记跟那篇 API 笔记其实是同一根因"的发现机会。本 spike 评估用向量搜索做自动关联的可行性。

## 2. 用户已决定的产品形态

| 维度 | 决策 |
|---|---|
| 关联算法 | 语义向量搜索（Vector Search）|
| 触发场景 | 查看某篇 Knowledge 文档时 |
| 展示位置 | 文档侧边栏，提示 `隐性关联 (Related Docs: N)` |
| Embedding 模型 | 待定（见 §3） |
| 向量存储 | 待定（见 §4） |
| 触发时机 | 待定（写时同步 / 异步 / 读时实时）|

## 3. Embedding 模型对比

| 候选 | 维度 | 中文 | 英文 | 1M token 成本 | 接入 | 备注 |
|---|---|---|---|---|---|---|
| **OpenAI text-embedding-3-small** | 1536 | ★★★★ | ★★★★★ | $0.02 | 复用现有 OpenAI 凭据 | **推荐** |
| OpenAI text-embedding-3-large | 3072 | ★★★★★ | ★★★★★ | $0.13 | 同上 | 精度高但贵 6 倍，Knowledge 场景 overkill |
| bge-large-zh-v1.5（开源本地）| 1024 | ★★★★★ | ★★★ | 0 | 需自托管推理服务 | 中文最强但要新起服务 |
| sentence-transformers/all-MiniLM-L6-v2 | 384 | ★★★ | ★★★★ | 0 | 同上 | 体积小、英文好 |

**推荐：OpenAI text-embedding-3-small**。理由：
1. 复用 axis-agent 已有 OpenAI 凭据与 `base-url`，零签约
2. 中文/英文均够用，Knowledge 笔记混合语言场景下表现稳定
3. 1536 维对 Knowledge 笔记规模（百到千篇）足够区分
4. 接口简单（`POST /v1/embeddings`），不引入新 SDK

## 4. 向量存储对比

| 候选 | 部署成本 | 与现有栈关系 | 适用规模 | 备注 |
|---|---|---|---|---|
| **pgvector（PostgreSQL 扩展）** | 极低 | **零额外组件**，复用现有 `postgres` 库 | < 100 万向量 | **推荐** |
| 单独向量库（Chroma/Milvus/Qdrant）| 高 | 新增服务、运维、连接管理 | > 100 万向量 | 当前规模不需要 |
| Elasticsearch dense_vector | 中 | 没装 ES | 大 | 已有全文搜索需求才考虑 |

**推荐：pgvector**。理由：
1. 现有 `application.yml` 已是 PostgreSQL，**装个扩展 + 加列** 即可
2. 避免引入新服务、新部署、新故障点
3. 个人工具规模（数百到数千篇笔记）远在 pgvector 性能上限内
4. 后续想换独立向量库，迁移也只是导出向量列

## 5. 存储设计（推荐方案）

```sql
-- 加扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- Knowledge 表加 embedding 列（pgvector 类型）
ALTER TABLE knowledge ADD COLUMN embedding vector(1536);

-- 索引（向量相似度查询性能）
CREATE INDEX knowledge_embedding_idx ON knowledge USING ivfflat (embedding vector_cosine_ops);
```

写入策略：
- 创建/更新 Knowledge 时，**异步**调 embedding API 算向量并回填（不阻塞用户保存）
- 失败重试 3 次，最终失败打日志不报错（可后续 batch 修复）
- 删除文档时同步删除 embedding（其实 row 删了 embedding 自然没了）

读取策略：
- 查询某文档关联：取该文档 embedding，SQL `ORDER BY embedding <=> $1 LIMIT 5`（pgvector 余弦距离）
- 阈值过滤：`distance < 0.3`（即相似度 > 0.7）才展示，避免噪音
- 排除自身

## 6. 模块归属

- **axis-service**：`KnowledgeEmbeddingService`（调 OpenAI embeddings API + 写 pgvector）/ `KnowledgeLinkService`（向量检索 Top N）/ KnowledgeRepository 加 embedding 字段
- **不需要 axis-agent**：纯检索不需要 LLM

## 7. 关键风险

1. **Backfill 历史数据**：现存 Knowledge 文档需要批量补 embedding。建议写个一次性脚本（`scripts/backfill-embeddings.sh`），分批 100 条/批，避免打爆 OpenAI 速率限制
2. **Embedding 模型升级**：未来换模型需重算所有 embedding。在 KnowledgeRepository 加 `embedding_model_version` 字段以便识别需要重算的 row
3. **成本**：1M tokens ≈ $0.02，单篇 Knowledge 笔记约 1-5k tokens ≈ $0.0001。千篇笔记 backfill 总成本 < $0.5，可忽略
4. **关联质量**：阈值定高了漏关联，定低了噪音多。建议初期阈值 0.7（distance 0.3），上线后看用户反馈调
5. **侧边栏 UX**：5 条关联是否合适？排序按相似度还是按时间？可在 design 阶段细化

## 8. 转正路径

按 **L 级** 走完整 feature 流程（涉及新扩展、新字段、backfill 脚本）。建议实施顺序：

1. **M1**：装 pgvector 扩展 + 加列 + backfill 脚本（无 UI，纯后台）
2. **M2**：`KnowledgeLinkService` 暴露 `/api/knowledge/{id}/related` 端点
3. **M3**：Knowledge 详情页侧边栏展示关联列表 + 跳转

## 9. 探索性结论

- **可行性**：明确可行。复用现有 OpenAI 凭据 + PostgreSQL 扩展，零新组件
- **不建议先做的事**：跨语言混合 embedding、关联结果解释（让 LLM 生成"为什么相关"——可作 v2）、Knowledge 与 Issue 跨表关联（先聚焦 Knowledge 内部）
- **不建议绕过的事**：embedding 模型版本记录（为未来升级铺路）、写入失败的优雅降级（避免坏一条阻塞整篇保存）