# 学习路线与面试准备

## 学习路线图（建议顺序，编号对应 feature-roadmap.md）

```
第 1 周：  #3 Structured Output (S) + #7 SSE 健壮性 (S) + #8 向量一致性 (S)
           → 三个小改动热身，顺便清掉已知 bug
第 2-3 周：#1 混合检索 + rerank → #2 两段式 RAG + citation
           → RAG 深度线，面试核心战场，配评测数字
第 4 周：  #4 Human-in-the-loop + #5 可观测性
           → Agent 安全线 + 生产化线
之后按余力：#6 上下文压缩 → #9 MCP → #10 LLM-as-judge → #11 reindex 任务化
```

每个 feature 按 `docs/workflow.md` 分级走（S 直接改，M 写 lite-spec）——本身也能讲："我用 spec-driven 流程 + AI 辅助开发完成这个项目"，呼应方法论沉淀。

---

## 面试叙事三段式

### 1. 每个 feature 讲"问题 → 方案 → 权衡 → 结果"

示例（分块器）：
- **问题**：Spring AI 自带 TokenTextSplitter 无重叠、不感知中文标点
- **方案**：自研 token 级分块器（jtokkit 计量 + 结构边界递归切分 + 整句重叠 + 标题前置）
- **权衡**：为什么不用 MarkdownDocumentReader（输入要 Resource，语料在 DB）、为什么不用 LLM metadata enricher（2.0 已移除）
- **结果**：golden 数据集回归测试，坏边界率 96% → 1.2%，阈值 ≤5% 进 CI 门禁

有数字的结果最硬。做完混合检索记得补命中率对比数字。

### 2. 主动暴露 1~2 个已知局限 + 改进计划

推荐讲这两个（清单见 existing-features.md 末尾）：
- "目前是单用户假设，ToolCallNotifier 不支持并发对话，生产化需要换 request-scoped 上下文"
- "检索目前是裸向量，混合检索 + rerank 已在路线图上"（做完后改讲别的局限）

主动讲比被问出来强十倍，且把节奏控制在准备好的领域。

### 3. 诚实边界

- 个人项目就说是个人项目，叙事是"工作 AI 落地有限，用个人项目把 Spring AI 生态完整实践一遍"。
- Python/LangChain 如果只是学过，别主动吹。被问到就答："调研过，选型对比后选了 Spring AI，因为技术栈统一、团队 Java 背景、Spring AI 2.0 抽象足够。"
- 所有讲出来的技术点必须是代码里真实存在的——面试官可能要求看代码或追问实现细节。

---

## 高频问题自检清单（能答才算准备好）

**Agent / Tool Calling**
- [ ] tool calling 的完整往返流程？tool 结果怎么回喂 LLM？
- [ ] Tool 返回值为什么设计成自然语言 + ID 而不是 JSON DTO？
- [ ] LLM 生成非法参数怎么办？（枚举 String 化 + 下沉校验）
- [ ] Agent 执行危险操作怎么防？（→ Human-in-the-loop）

**RAG**
- [ ] 完整 RAG 链路？分块策略怎么定的？chunk size 依据？
- [ ] 纯向量检索的局限？混合检索怎么融合？（RRF）
- [ ] rerank 是什么？为什么需要？
- [ ] 怎么防幻觉？（grounding prompt + citation + 阈值过滤）
- [ ] embedding 模型怎么选？dimensions 为什么固定 1536？

**流式 / 架构**
- [ ] SSE vs WebSocket 选型理由？
- [ ] 背压怎么处理的？Flux.merge 是什么？
- [ ] tool 事件怎么插进 token 流的？（AtomicReference 隐式上下文）
- [ ] 前端怎么处理 UTF-8 跨 chunk 截断？

**记忆 / 上下文**
- [ ] 短期/长期记忆分别怎么实现？为什么分开？
- [ ] 长对话上下文超限怎么办？（窗口 → 摘要压缩）
- [ ] 长期记忆为什么注入 system prompt 而不是走 RAG？

**生产化**
- [ ] LLM 调用失败怎么办？（降级链逐层讲）
- [ ] 怎么控制成本？（缓存、预算、token 追踪）
- [ ] 怎么保证摘要质量？（golden 评测 + 通过率阈值 + LLM-as-judge）
- [ ] 模型切换怎么做？（档案 + volatile 热切换）
