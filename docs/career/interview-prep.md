# 学习路线与面试准备

## 学习路线图（建议顺序，编号对应 feature-roadmap.md）

```
已完成：  #3 Structured Output + #1 混合检索 rerank + #2 RAG 问答 citation + #4 Human-in-the-loop
           → P0 全收官
           #8 向量一致性 + #7 SSE 健壮性（error 帧 / 手动重试 / aiExpand decoder）
           → "发现即修复"叙事闭环
           #5 可观测性（L0 llm_call_log + L1 用量面板 + L2 双层指标）
           → 生产化板块弱答案补齐；三层 + 流式末帧 usage 是现成深挖素材
           #6 上下文压缩（预压缩滚动摘要，失败降级硬截断）
           → 记忆板块完整答案：截断 → 滑窗 → 摘要压缩，谱系讲到第三层
剩余（评估后的结论）：
  #9 MCP——项目无真实消费方场景，不做进代码；半天调研在 docs/spike/ 留结论，
    能讲架构 + "评估后不接"的决策即可
  #10 LLM-as-judge——只做 editor pass 评测（补自己坦承的洞），RAG judge 留作"后续规划"话术
  #11 reindex 任务化——面试价值≈0，产品需要时再做
```

每个 feature 按 `.agents/skills/` 技能流分级走（S 直接改，M 写 lite-spec 落 `.scratch/<feature>/`）——本身也能讲："我用 spec-driven 流程 + AI 辅助开发完成这个项目"，呼应方法论沉淀。

---

## 面试叙事三段式

### 1. 每个 feature 讲"问题 → 方案 → 权衡 → 结果"

示例（分块器）：
- **问题**：Spring AI 自带 TokenTextSplitter 无重叠、不感知中文标点
- **方案**：自研 token 级分块器（jtokkit 计量 + 结构边界递归切分 + 整句重叠 + 标题前置）
- **权衡**：为什么不用 MarkdownDocumentReader（输入要 Resource，语料在 DB）、为什么不用 LLM metadata enricher（2.0 已移除）
- **结果**：golden 数据集回归测试，坏边界率 96% → 1.2%，阈值 ≤5% 进 CI 门禁

有数字的结果最硬。混合检索已拿到可讲的数字：hit@1 0.79→0.96、hit@3/5→1.00（24 条 golden，真实 PG + embedding）。

### 2. 主动暴露 1~2 个已知局限 + 改进计划

推荐讲这两个（清单见 existing-features.md 末尾）：
- "目前是单用户假设，ToolCallNotifier 不支持并发对话，生产化需要换 request-scoped 上下文"
- "摘要压缩有信息损耗——谱系上更进一步是向量化检索历史，但当前语料量级用不上"

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
- [ ] LLM 流中途失败前端怎么感知？（响应头已提交，HTTP 层无法再报错——应用层 error 帧 + `onErrorResume` 映射 + done 照常收尾）
- [ ] 失败消息手动重试为什么要先去重 user 消息？（`MessageChatMemoryAdvisor` 流式开始前就落库 user 消息——读 Spring AI 源码证实的坑）

**记忆 / 上下文**
- [ ] 短期/长期记忆分别怎么实现？为什么分开？
- [ ] 长对话上下文超限怎么办？（窗口 + 滚动摘要压缩——预压缩最旧 30 条进 system prompt；谱系：截断 → 滑窗 → 摘要压缩 → 向量化检索历史；为什么不无限拉长 prompt：成本、注意力稀释）
- [ ] 压缩什么时候触发、失败了怎么办？（请求开始前预检查——advisor 流式前就落 user 消息；失败静默降级硬截断，不删不改）
- [ ] 长期记忆为什么注入 system prompt 而不是走 RAG？

**生产化**
- [ ] LLM 调用失败怎么办？（降级链逐层讲 + 流式 error 帧）
- [ ] 怎么控制成本？（缓存、预算、token 追踪）
- [ ] 怎么知道 LLM 花了多少钱、慢在哪？（`llm_call_log` 事实日志 + 用量面板 + 业务层 `llm.*` / 模型层 `gen_ai.*` 双层指标；流式 usage 只在末帧怎么拿到的？）
- [ ] 观测日志怎么不拖垮主链路？（@Async 有界队列 + 静默失败；cancel 为什么不记日志？）
- [ ] 怎么保证摘要质量？（golden 评测 + 通过率阈值 + LLM-as-judge）
- [ ] 模型切换怎么做？（档案 + volatile 热切换）
