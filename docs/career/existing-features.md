# 现有可包装的 Feature（已实现的 7 个）

每个 feature 结构：简历写法 → 背后技术（要能讲出来的）→ 面试官会深挖什么。

---

## 1. 多领域 Tool Calling 的 AI Agent（核心卖点，放简历第一条）

**简历写法**：基于 Spring AI Tool Calling 实现 AI Agent，将 Inbox/Issue/Project/Knowledge/Memory 五个业务领域的 20+ 操作封装为 LLM 工具，用户通过自然语言完成任务管理（如"把这个灵感转成 Issue 并设为高优先级"）。

**背后技术**：
- **Tool 设计契约**：Tool 方法返回带实体 ID 的自然语言文本（`"✅ 已创建 Issue #abc123"`）而非 DTO——返回值直接作为 tool response 回喂 LLM，ID 包含在内才能支持**链式操作**（"把刚才那个标为完成"）。
- **参数防御设计**：枚举以 String 传递 + `@ToolParam` description 约定合法值，校验下沉到领域 Service——避免 LLM 生成非法枚举导致反序列化崩溃（`axis-agent/.../tool/IssueTool.java`）。
- **复用领域 Service** 而非直连 Repository：Tool 与 REST API 走同一套业务逻辑，校验不绕过。
- **传输层与事件模型解耦**：`ChatEvent` 用 sealed interface（Token/Tool/Done），Controller 只做 pattern matching 映射成 SSE 帧。

**面试官深挖**：tool 调用完整往返流程（LLM 返回 tool_calls → 框架执行 → 结果回喂 → 继续生成）；LLM 胡乱调 tool 怎么办（→ 引出 roadmap 的 Human-in-the-loop）；多 tool 串行调用的上下文传递。

---

## 2. 双流合并的流式 SSE 架构（差异化亮点）

**简历写法**：设计结构化 SSE 帧协议（token/tool/done 三类帧），基于 Reactor `Flux.merge` 实现 LLM token 流与工具调用事件流的实时交织推送，前端即时展示"正在创建 Issue…"等 Agent 行为。

**背后技术**：
- 核心难点：**Spring AI 的 tool 调用发生在框架内部调用栈，Tool 方法拿不到响应流**。解法：全局 `AtomicReference<Sinks.Many>` 隐式上下文传递，`begin()` 挂 sink，tool 方法内 `emit()`，两路流 `Flux.merge` 按到达顺序交织（`AgentService.java` + `ToolCallNotifier.java`）。
- 背压：`Sinks.many().multicast().onBackpressureBuffer()`。
- 前端：`fetch + ReadableStream`（`$fetch` 不支持流式）、`TextDecoder({stream:true})` 处理多字节 UTF-8 跨 chunk 截断、增量行缓冲处理不完整帧——三个细节都是真实踩坑点。
- `reactive()` 包装流中消息，字符串追加直接驱动 Vue 视图。

**面试官深挖**：WebFlux 背压机制；SSE vs WebSocket 选型（LLM 场景单向、文本、HTTP 友好，SSE 够用）；取消与断连处理（→ 引出 roadmap 的 SSE 健壮性）。

---

## 3. 双层对话记忆体系

**简历写法**：实现"短期滑动窗口 + 长期自主记忆"的双层记忆架构——短期记忆经 JPA 持久化支持 100 条消息窗口的跨会话还原；长期记忆由 Agent 自主判断保存并注入 system prompt，实现跨会话个性化。

**背后技术**：
- `JpaChatMemoryRepository` 实现 Spring AI `ChatMemoryRepository` 接口，`MessageWindowChatMemory`（maxMessages=100）；窗口裁剪语义是"saveAll 传完整窗口"，先删后插全量替换，实现极简。
- **务实取舍（面试金句）**：只落 USER/ASSISTANT 消息，tool 中间过程不进短期记忆——回放上下文干净，避免 tool 噪声挤占窗口 token。
- 长期记忆 = Agent 自主 `saveMemory` + 每请求现拼 system prompt（≤50 条，**倒序取**——正序会让第 51 条永远进不了 prompt，这个 bug 直觉值得讲）。
- 会话语义标题：首条消息截断兜底 + `@Async` LLM 生成 4~10 字标题覆盖 + 前端 3s 延迟补刷。

**面试官深挖**：窗口截断丢失早期上下文怎么办（→ 引出 roadmap 的上下文压缩）；长期记忆为什么放 system prompt 而不是 RAG（量级小、强相关、避免检索噪声）；记忆冲突/遗忘（可作"后续规划"讲）。

---

## 4. RAG 知识库（自研分块器——最硬的 RAG 证据）

**简历写法**：基于 pgvector + Spring AI EmbeddingModel 构建知识库语义检索；针对 Spring AI 自带 TokenTextSplitter 无重叠、无中文标点感知的问题，自研 token 级分块器（结构边界切分 + 整句重叠 + 标题前置），配套 golden 数据集回归测试将坏边界率从 96% 降至 1.2%。

**背后技术**：
- **分块器细节**：jtokkit CL100K 精确 token 计量；递归分隔符优先级（`\n\n` → `\n` → 中文标点 → 英文标点）；贪心装箱合并；≤500 token 块 + ≤50 token 整句重叠；标题前置预算从块额度里扣。
- **"为什么不用现成组件"有明确论证**（`TokenTextSplitter` 无 overlap 无中文支持、`MarkdownDocumentReader` 输入要 `Resource`）——资深工程师的决策方式。
- **golden 数据集 + 确定性回归测试**（`KnowledgeChunkerGoldenTest`，无网络、`mvn test` 默认跑）：坏边界率 96%→1.2%，阈值 ≤5% 做门禁。"AI 功能可评测"意识是稀缺加分项。
- embedding 固定 `dimensions=1536` 对齐 `vector_store` 表（兼容智谱 embedding-3 等可变维度模型的坑）；`FilterExpressionBuilder` 按 `item_id` 元数据过滤删除实现幂等 reindex。
- 降级链：pgvector 缺失 → 关键词 LIKE 检索；embedding 调用失败 → 关键词检索 + WARN。

**面试官深挖（目前薄弱，正好引出 roadmap P0）**：只有单向量检索，没有混合检索/rerank/阈值/query 改写；chunk size 怎么定的；embedding 模型怎么选。

---

## 5. 多 Provider AI 配置档案 + 热切换

**简历写法**：设计 DB 驱动的多 Provider AI 配置体系（CHAT/EMBEDDING 双类型档案），API Key AES-256 加密存储；基于 volatile 引用原子替换 + 事件驱动实现 ChatClient 与向量库的热切换，切换模型无需重启、在途请求无感知。

**背后技术**：
- `volatile ChatClient` + `synchronized reload()` 整体重建后原子替换（读侧无锁）；`AiConfigReloadedEvent` 驱动 `PgVectorStore` 同步重建。
- 为此排除 `OpenAiChatAutoConfiguration`、关闭 vectorstore 自动装配，**把模型构建权从 starter 手里收回手工管理**——体现对 Spring 机制的理解。
- 解密失败回退 env 的兜底链；每类同时最多一个激活档案；EMBEDDING 档案用真实 `embed("ping")` 调测连通性。

**面试官深挖**：为什么不直接用 starter 的配置；热切换时在途请求怎么办（旧 client 继续用，自然结束）；key 加密的密钥管理局限（→ 可聊 KMS 作为生产化改进）。

---

## 6. Daily Digest 2.0 —— LLM 内容生成管线（工程化程度最高）

**简历写法**：实现 RSS→分类→LLM 精读摘要→主编润色的每日技术摘要管线；通过按文章链接的摘要缓存（prompt SHA-256 版本化）、单次重试 + 多级降级、唯一约束幂等，将 LLM 日调用量控制在 17 次以内，端到端可重跑、不空转。

**背后技术（每条都是生产级意识）**：
- **成本控制**：`article_summary_cache` 按 link 主键缓存，命中零 LLM 调用；日预算 ≤17 次（16 篇 + 1 次 editor pass）。
- **多级降级链**：LLM 摘要失败 → 重试 1 次（追加 schema 提醒）→ 降级 RSS 简介；editor pass 失败 → 降级关键词分类版。digest 永远不会空。
- **幂等分层**：`digest_date` 唯一约束（硬保证）+ 状态机（PENDING/COMPLETED/FAILED）+ 重跑先删后写；并发竞争 catch `DataIntegrityViolationException`。
- **可评测**：`SummarizationEval` 用 20 条 golden 用例 + 真实 LLM 调用，断言字段非空 + 长度通过率阈值（85%/90%）；无 API key 时 `assumeTrue` 跳过不红 CI。
- 并发抓取：专用线程池 + `CompletableFuture` 并行抓 7 个 RSS 源，单源失败吞掉不阻塞。

**面试官深挖**：JSON 输出怎么保证格式（目前是字符串解析 + 重试，**主动坦承 → 引出 roadmap 的 Structured Output**）；prompt 改了缓存怎么办（prompt_version 记了但不参与键——坦诚是权衡）；editor 评测未落地（坦诚 + 规划）。

---

## 7. AI 产物异步生成管线（次级，一两句话带过）

知识条目 SUMMARY/MINDMAP 生成：`@TransactionalEventListener(AFTER_COMMIT)` + `@Async` 线程池 + PENDING→GENERATING→DONE/FAILED 状态机 + in-flight 去重，失败只 WARN 不影响主流程；前端 3s 轮询 + 90s 超时 + FAILED 重试。"AI 任务异步化"标准模式。

---

## 已知局限清单（面试时主动暴露，掌握节奏）

1. **单用户假设**：`ToolCallNotifier` 用全局 `AtomicReference` 持有当前 sink，不支持并发对话；生产化需换 request-scoped 上下文。
2. **检索是裸向量**：无混合检索/rerank/阈值（→ roadmap P0 已规划）。
3. **危险操作无确认**：Agent 可直接 delete（→ roadmap P0 已规划 Human-in-the-loop）。
4. **上下文硬截断**：100 条窗口外历史直接丢（→ roadmap P1 上下文压缩）。
5. **编辑条目不同步向量**：`update()` 不触发 reindex，向量与 DB 会漂移（→ roadmap P1）。
