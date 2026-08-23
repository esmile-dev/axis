---
title: AI Chat 架构：SSE 流式、Tool Calling 与双层记忆
slug: ai-chat-architecture
description: 讲清 AI Chat 的端到端实现——前端 ReadableStream 手动解 SSE、后端 Flux.merge 合并 token 流与工具事件流、Spring AI Tool Calling 操作五大模块、短期记忆（滑动窗口 + JPA 持久化）与长期记忆（Tool 主动写入 + prompt 注入）的双层设计，以及运行时模型热切换。
status: knowledge
tags: [spring-ai, sse, webflux, reactor, tool-calling, chat-memory, agent, llm, architecture]
created: 2026-08-03
---

# AI Chat 架构：SSE 流式、Tool Calling 与双层记忆

> 由浅入深讲清楚：一条用户消息从浏览器到 LLM 再回来的完整旅程，重点展开三个面试高频话题——**流式响应怎么做**、**Agent 怎么操作业务数据**、**记忆怎么分层**。

---

## 1. 全景：一条消息的旅程

```
chat.vue 输入 → useChat.send()（frontend/app/composables/useChat.ts:95）
   │  fetch POST /api/agent/chat {message, sessionId=前端 UUID}
   │  （$fetch 不支持流式，用原生 fetch + ReadableStream）
   ▼
AgentController.chat()（backend/backend/src/main/java/com/esmile/axis/chat/AgentController.java）
   │  @Valid ChatRequest DTO（Bean Validation 拦截空消息）→ 一行委托 AgentService
   ▼
AgentService.chat()（backend/backend/src/main/java/com/esmile/axis/chat/AgentService.java）
   │  ① AiConfigService.get()          取当前激活 profile 构建的 ChatClient（可热切换，见 02 号文档）
   │  ② systemPrompt()                 基础 prompt + chat_long_memory 长期记忆（≤50 条）
   │  ③ MessageChatMemoryAdvisor       按 sessionId 从 chat_message 读窗口历史注入 prompt
   │  ④ .tools(5 个 Tool)              模型返回 tool_call 时 Spring AI 反射调用 Tool 方法
   │       └─ Tool 首行 ToolCallNotifier.emit(label) → Sinks.Many → 合并进事件流
   │  ⑤ .stream().content()            token 逐个产生 → 映射为 ChatEvent.Token
   │  ⑥ 结束后 advisor 把裁剪窗口写回 chat_message（仅 USER/ASSISTANT）
   ▼
Flux.merge(tokenFlux, toolFlux) → Controller 把 ChatEvent 映射为 SSE JSON 帧 → text/event-stream
   ▼
前端 ReadableStream 逐行解析 data: JSON 帧 → token 增量渲染 / tool 状态行
```

三个端点（`AgentController.java`）：

| 端点 | 返回类型 | 用途 |
|---|---|---|
| `POST /api/agent/chat` | `Flux<ServerSentEvent<Map>>` | 主对话，结构化帧（token/tool/done） |
| `POST /api/agent/chat/sync` | `Map<String,String>` | 非流式 `.call().content()`，调试/简单场景 |
| `POST /api/agent/expand` | `Flux<String>` | PRD 扩写，纯文本流，只挂 knowledgeTool |

---

## 2. 流式响应：SSE 双流合并

### 2.1 帧格式是结构化 JSON，不是纯文本

普通 SSE 聊天 demo 直接 `data: 文本chunk`，本项目每帧是 JSON（`AgentController.toSse()` 把 `ChatEvent` 映射为帧）：

```
data: {"type":"token","text":"好的"}

data: {"type":"tool","label":"创建 Inbox 条目"}

data: {"type":"token","text":"，已记录"}

data: {"type":"done"}
```

为什么？因为 Agent 会**调工具**，前端要实时显示「正在调用：创建 Inbox 条目」这种状态行，纯文本帧表达不了。三种帧：token（文本增量）、tool（工具调用事件）、done（结束标记）。

### 2.2 两条流的来源不同，用 Flux.merge 合并

token 流来自 Spring AI（`chatClient.stream().content()`），工具事件流来自一个 `Sinks.Many`：

```java
// AgentService.chat()（精简）
Sinks.Many<ChatEvent> toolEvents = toolCallNotifier.begin();

Flux<ChatEvent> tokenFlux = chatClient().prompt()
        .system(systemPrompt()).user(message)
        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))
        .tools(inboxTool, issueTool, projectTool, knowledgeTool, memoryTool)
        .stream().content()
        .<ChatEvent>map(ChatEvent.Token::new)
        .concatWith(Flux.just(new ChatEvent.Done()))   // 流尾追加 done 事件
        .doFinally(signal -> toolCallNotifier.end());  // 含异常/取消都清理

return Flux.merge(tokenFlux, toolEvents.asFlux());
```

`ChatEvent` 是 sealed 接口（`Token` / `Tool` / `Done` 三个 record），与传输层解耦——SSE 帧格式（`{"type":...}`）由 Controller 的 `toSse()` 统一映射。编排在 Service 只有一份，`/chat` 与 `/chat/sync` 共享同一套 advisor/tools 装配，改走 `.stream()` 或 `.call()` 的区别。

关键设计点：

- **`concatWith(done)`**：token 流自然结束后追加 done 帧，前端拿到明确的终止信号（当前前端实际靠流结束判断，done 帧是协议上的冗余保险）。
- **`doFinally` 清理 sink**：无论正常结束、异常还是前端断连取消，都要 complete 掉工具事件流，否则内存泄漏 + 下次请求拿到脏 sink。
- **`Flux.merge` 不保证两条流的相对顺序**：token 和 tool 事件按各自产生时序交错推给前端，这正是想要的效果（工具在两次 token 之间执行，事件也出现在中间）。

### 2.3 ToolCallNotifier：工具线程 → SSE 流的桥

Spring AI 调 Tool 方法发生在它自己的执行栈里，和 SSE 响应流是两条线。`ToolCallNotifier.java` 用 `AtomicReference<Sinks.Many>` 搭桥：

- `begin()`：请求开始时创建 `Sinks.many().multicast().onBackpressureBuffer()` 并存入原子引用
- `emit(label)`：每个 Tool 方法首行调用，发 `{"type":"tool","label":"创建 Inbox 条目"}`
- `end()`：请求结束 complete 并清空
- **无活跃 sink 时 emit 是空操作**——`/api/agent/expand`、Daily Digest 也复用这些 Tool，但不走 chat 的 SSE，天然不受影响

**已知约束**：`AtomicReference` 全局只有一个 sink，建立在「单用户、同一时刻只有一次对话」的假设上（类注释 `:12-15` 写明了）。多人并发需要改成按 sessionId 路由的 `Map<String, Sinks.Many>`。这是个人项目的合理取舍，面试时主动讲出来比被问出来好。

---

## 3. Tool Calling：Agent 怎么操作业务数据

### 3.1 声明：注解即 schema

Spring AI 2.0 的 Tool 是普通 Spring 组件 + 注解（`InboxTool.java:24-30`）：

```java
@Component
@RequiredArgsConstructor
public class InboxTool {
    private final InboxService inboxService;          // 直接复用业务 Service，不另写一套
    private final ToolCallNotifier toolCallNotifier;

    @Tool(description = "在 Inbox 中创建一条新的灵感/想法记录")
    public String createInboxItem(
            @ToolParam(description = "灵感/想法的内容") String content) {
        toolCallNotifier.emit("创建 Inbox 条目");
        InboxItem item = inboxService.create(content);
        return "✅ 已创建 Inbox 条目：" + item.getContent() + " (ID: " + item.getId() + ")";
    }
}
```

`.tools(...)` 注册后，Spring AI 从注解生成 tool schema 发给模型；模型返回 tool_call 时框架反射调用方法，**把返回值作为 tool 消息回填对话**，模型再基于结果生成给用户的最终回复。一次用户消息可能触发多轮 tool_call → 回填 → 再生成。

### 3.2 三个容易忽略的设计决策

1. **description 用中文写、写得像产品文档**。模型靠 description 决定「什么时候调哪个工具、参数怎么填」，写不清楚模型就乱调。这本质上是 prompt engineering 的一部分——workflow.md 也把「Prompt 与工具定义视同接口契约」。
2. **返回值是给模型看的自然语言，不是给程序解析的 JSON**。返回 `✅ 已创建 Inbox 条目：xxx (ID: 9)` 而不是 `{"id":9}`——模型会把 ID 用在后续操作里（「把刚才那条标记完成」→ 模型从上下文拿到 ID 调 `markInboxDone`）。返回 ID 是关键，否则连续操作会断链。
3. **Tool 直接注入业务 Service 层**（InboxService、IssueService……），不做任何绕过。业务校验、实体状态检查全部复用既有分层——Agent 只是一个新的「调用方」，不是新的「业务层」。

五个 Tool 的分工：`InboxTool`（4 方法）、`IssueTool`（5 方法）、`ProjectTool`（3 方法）、`KnowledgeTool`（3 方法）、`MemoryTool`（3 方法，见 §4.4）。

---

## 4. 记忆系统：短期 + 长期的双层设计

这是整套架构里信息密度最高的部分，也是面试最容易深挖的点。

### 4.1 为什么记忆要分层

LLM 的上下文窗口有限且按 token 计费，「记住一切」既不可能也不必要。本项目的分层：

| 层 | 存什么 | 生命周期 | 实现 |
|---|---|---|---|
| 短期记忆 | 当前会话的对话历史 | 会话内，窗口外丢弃 | `chat_message` 表 + 滑动窗口 100 条 |
| 长期记忆 | 跨会话的用户偏好/事实（「我用 pnpm」「我是后端工程师」） | 永久，用户/Agent 可删 | `chat_long_memory` 表 + prompt 注入 |

### 4.2 短期记忆：Advisor 链 + JPA 持久化

**注入链路**（Spring AI 的 Advisor 是 ChatClient 的 AOP 机制，请求前后各切一刀）：

```
请求前：MessageChatMemoryAdvisor 按 conversationId 从 ChatMemory 读历史 → 插到 messages 前面
LLM 调用
请求后：advisor 把「本轮新消息」交给 ChatMemory → 裁剪窗口 → 持久化
```

配置（`backend/backend/src/main/java/com/esmile/axis/chat/AiConfig.java:17-22`）：`MessageWindowChatMemory`（`maxMessages=100`）+ 自定义 `JpaChatMemoryRepository`。

**会话隔离**：`.advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, sessionId))`（`AgentService.chat()`）——conversationId 不从请求体猜，而是显式传进 advisor 上下文。key 必须用框架常量 `ChatMemory.CONVERSATION_ID`（值是 `"chat_memory_conversation_id"`，advisor 内部按此 key 读取）：手写字面量拼错了编译器不报错，运行时会静默退回默认会话 `"default"`，所有会话历史混一起。sessionId 由前端 `crypto.randomUUID()` 生成（`useChat.ts:79,99`），前后端零协商成本。

**持久化实现**（`backend/backend/src/main/java/com/esmile/axis/chat/JpaChatMemoryRepository.java`）的三个关键语义：

1. **全量替换**：`MessageWindowChatMemory` 每次 `saveAll` 传入的是**裁剪后的完整窗口**（不是增量），所以 `saveAll()` 先 `deleteByConversationId` 再全量插入（`:52-71`）——追加式写入会导致重复堆积、被淘汰的旧消息永远删不掉。顺带地，`seq` 是窗口内序号，窗口滑动后位置全部前移，全量重写也免去了逐条 diff 和重排。方法级 `@Transactional` 保证「删 + 插」原子，不会出现「删完了插入挂了、历史凭空消失」的中间态。窗口外的旧消息随之被物理删除——「历史展示」和「模型记忆」共用一张表，前端看到的会话历史就是模型实际看到的内容，两者永远不会不一致。
2. **只落 USER/ASSISTANT**：SYSTEM/TOOL 中间消息不落库（`:59-61`）。工具调用的中间态回放给模型没有价值（还会消耗 token），回放时还原成纯文本对话（`toSpringMessage`，`:80-86`）。
3. **顺带维护会话元数据**：`saveAll` 里 `upsertConversation`（`:88-99`）——首次用首条用户消息截 30 字（code point 截断，避免切乱 emoji）当**兜底标题**，之后刷 `updatedAt` 让会话列表按最近活跃排序。首轮对话结束后 `ChatHistoryService.generateAndUpgradeTitle()`（`@Async`）用 LLM 生成语义标题覆盖兜底，失败静默保留截断版——用户立刻有标题，体验只升不降。

对比默认的 `InMemoryChatMemoryRepository`：重启即丢、无法支撑前端历史页。实现 `ChatMemoryRepository` 这个 SPI 换 JPA 是标准扩展点，成本约 100 行。

### 4.3 四种消息类型：一轮对话产生五条消息，落库的只有两条

Spring AI 的 `MessageType` 有四种（对应 OpenAI 的 role）：**SYSTEM / USER / ASSISTANT / TOOL**。一次带工具调用的对话（「帮我记下：下周看看 Nuxt 4」），内部实际产生五条消息：

```
[SYSTEM]    基础 prompt + 长期记忆（每次请求现拼，不落库）
[USER]      用户输入                                              → 落库
[ASSISTANT] 工具调用决策（文本为空，携带 tool name + 参数 JSON）     → 不落库
[TOOL]      Tool 方法返回值字符串（ToolResponseMessage）            → 不落库
[ASSISTANT] 基于工具结果的最终回复                                   → 落库
```

落库闸门是 `JpaChatMemoryRepository.saveAll()` 里那个 `continue`（`:59-61`）：`type != USER && type != ASSISTANT` 就跳过。回放侧镜像：`toSpringMessage`（`:80-86`）只认两种 role，其余 `Optional.empty()` 滤掉。注意落库时 ASSISTANT 也只存 `getText()` 纯文本——tool_calls 元数据本身被丢弃，回放出来的历史是干净的「一问一答」，不存在半截的工具调用对。

**TOOL 消息为什么不该存**（判断标准：这条结果是「状态快照」还是「不可再生内容」）：

1. **五个 Tool 全是 PG 的 CRUD，结果随时可重查**。快照从写入那一刻起就在腐坏——把过期的 issue 列表喂给模型，它可能引用旧状态而不去重查，是负资产。模型需要时再调一次 list 工具，拿到的才是最新数据。
2. **有价值的残渣已留在 ASSISTANT 文本里**（「已创建 Issue：xxx (ID: …)」）——信息没丢，丢的只是中间过程。工具消息是「过程的脚手架」，对话文本是「拆完脚手架的建筑」。
3. **API 配对约束**：OpenAI 兼容接口要求 tool 消息和 assistant 的 tool_calls 按 id 严格成对。要存就得连 tool_call_id、参数 JSON 一起存——`chat_message` 表结构（role/content/seq）表达不了，半吊子持久化回放时直接 400。
4. **token 成本**：窗口内容每轮重发，工具结果是「写一次、以后每轮白交钱」，直到被挤出窗口。
5. **真想留的有专属通道**：跨会话值得记的信息由 MemoryTool 主动甄别写入（§4.4）；自动落库所有工具结果是无差别囤积，与「长期记忆要精选」的哲学冲突。

配套约定：**Tool 返回值刻意紧凑**——写操作返回一行确认、列表逐行文本、`searchDocuments` 内容截断前 100 字，绝不返回完整实体 JSON。单条 TOOL 消息因此维持在几十字节~几 KB。如果未来加了返回大 payload 的工具（读文档全文、抓网页总结），更要坚持不落库，并让工具把不可再生结果写进知识库/Inbox，而不是留在上下文里。

审计/排障/前端展示「当时调了什么」是**日志需求，不是记忆需求**——要做就单独建 tool_call 记录表（谁、何时、参数、结果、耗时），绝不混进 `chat_message`：一张表给模型当上下文，一张表给人当历史，两个目的不能合并。

### 4.4 长期记忆：Tool 主动写入 + prompt 注入

设计上有意思的地方在于：**写入时机由 Agent 自己决策，而不是规则代码**。

- **写入**：`MemoryTool.saveMemory` 是一个普通 Tool，system prompt 里明确告诉模型何时调用（`AgentService.systemPrompt()`）：「当用户表达值得跨会话记住的偏好、习惯或重要事实（或明确要求『记住』）时，调用 saveMemory」。
- **注入**：`systemPrompt()` 每次请求把 `chat_long_memory` 拼在基础 prompt 尾部（`:61-64`），上限 50 条。注意必须 `findTop50ByOrderByCreatedAtDesc`（取**最新** 50 条）——用 Asc 会让第 51 条起的记忆永远进不了 prompt（2026-08-03 修复）。
- **管理**：用户也可在聊天页「长期记忆」Dialog 里手动查看/删除（REST 走 chat 域包的 `ChatHistoryController`，`/api/agent/memories*`）。

为什么走 prompt 注入而不是塞进对话历史？因为长期记忆是**系统级上下文**（「你是谁、用户是谁」），不是对话内容；放 system prompt 里模型权重最高，也不会被滑动窗口挤掉。

### 4.5 记忆方案对比（面试必问：为什么不用 XX）

| 方案 | 机制 | 优点 | 缺点 | 本项目 |
|---|---|---|---|---|
| 滑动窗口 | 保留最近 N 条 | 简单、确定性强、无额外调用 | 窗口外全忘 | ✅ 短期记忆（N=100） |
| 摘要压缩 | 窗口外历史让 LLM 压成摘要 | 保留更多信息 | 每次多一次 LLM 调用；摘要漂移 | 未采用（窗口 100 对个人场景够用） |
| 显式长期记忆 | Agent 主动抽取事实存库 | 精准、可审计、用户可删 | 依赖模型判断「什么值得记」 | ✅ 长期记忆（MemoryTool） |
| 向量检索（RAG） | 历史 embedding 入库，按相似度召回 | 理论上无限记忆 | 架构重（向量库/embedding 模型）；召回噪声 | 未采用，见 §6 |

一句话话术：**短期靠滑动窗口保证「聊得下去」，长期靠 Tool 主动抽取保证「重启还记得」，两者都不需要引入向量数据库这种重资产**。

---

## 5. 运行时模型热切换（详见 02 号文档）

与聊天架构直接相关的两点：

- `ChatClient` **不是 Spring Bean**：由 `AiConfigService` 持有 `volatile currentClient`，激活/编辑 profile 时 `reload()` 整体替换引用。`AgentService` 注入 `AiConfigService` 每次调 `.get()`——直接注入 Bean 拿到的会是启动时的旧实例。
- 手动 `OpenAiChatModel` + `ChatClient.create()` 构建，绕过 Spring Boot 自动配置；且**故意不设 temperature**（Kimi coding 模型只接受默认值 1）。
- 聊天 Agent 和 Daily Digest 共用同一个 client——切模型对两个功能同时生效。

API key 加密存储、掩码返回、401 排查的完整链路见 `02-ai-config-api-key-lifecycle.md`。

---

## 6. 已知权衡与局限（面试坦诚项）

1. **ToolCallNotifier 单并发假设**：全局一个 sink，多并发对话会串事件。个人项目可接受，升级路径明确（按 sessionId 分桶）。
2. **知识库搜索是内存关键词过滤**（`KnowledgeTool.java:50-53`），不是向量检索。规模小（个人知识库几十篇）时关键词足够；上 RAG 需要 embedding 模型 + 向量库，收益/成本比不划算。这是**有意识的取舍**，不是不会做。
3. **危险工具操作无前端确认**：`deleteIssue`、`deleteInboxItem` 这类不可逆操作，模型判断要删就直接删了。workflow.md §7.3 要求危险动作默认需人确认，本期在 lite-spec 里显式豁免，后续单独做一个 M 级（SSE 帧加一个 `confirm` 类型 + 前端确认弹窗 + 后端挂起/恢复）。
4. **短期记忆窗口外物理删除**：想恢复窗口外历史做不到。设计上的解释是「历史 = 记忆」，但如果未来要做「长历史归档 + 记忆窗口」分离，需要加一张归档表。

---

## 7. 面试知识点速查

| 话题 | 一句话版本 | 代码锚点 |
|---|---|---|
| SSE 结构化帧 | token/tool/done 三类 JSON 帧，工具调用对前端可见 | `AgentController.toSse()`、`ChatEvent.java` |
| Reactor 双流合并 | token 流（Spring AI）+ tool 事件流（Sinks.Many）`Flux.merge`，`doFinally` 清理 | `AgentService.chat()`、`ToolCallNotifier.java` |
| Advisor 机制 | ChatClient 的 AOP：请求前注入历史、请求后持久化 | `AgentService.chat()` |
| 会话隔离 | conversationId 走 advisor 参数（key 用框架常量 `ChatMemory.CONVERSATION_ID`，别手写字面量），前端 UUID 生成 | `useChat.ts:79,99` |
| 消息类型与落库 | 四种 MessageType；一轮工具调用产生 5 条消息、只落 USER/ASSISTANT 两条；TOOL 是可重查的状态快照，进记忆窗口是负资产 | `JpaChatMemoryRepository.java:59-61` |
| 短期记忆 | `MessageWindowChatMemory`(100) + JPA 版 `ChatMemoryRepository`，saveAll 全量替换、只存 USER/ASSISTANT | `JpaChatMemoryRepository.java:52-71` |
| 长期记忆 | Agent 经 MemoryTool 主动写入 + 每次请求 system prompt 注入（≤50 条） | `AgentService.systemPrompt()`、`MemoryTool.java` |
| Tool Calling | `@Tool` 注解生成 schema，框架反射调用，自然语言返回值回填（返回 ID 支撑连续操作） | `InboxTool.java:24-30` |
| 模型热切换 | volatile 引用原子替换，ChatClient 非 Bean，DB profile 优先 env 兜底 | `AiConfigService.java`、02 号文档 |
| 前端流式 | fetch + ReadableStream + TextDecoder 按行解 `data:` 帧（$fetch 不支持流式），reactive 包装增量渲染 | `useChat.ts:114-150` |
| MVC/WebFlux 共存 | Tomcat servlet 栈上返回 `Flux<ServerSentEvent>`，不换服务端栈 | 01 号文档 |

---

## 8. 一句话总结

> AI Chat 的本质是「**一次请求、两条流、三层上下文**」：SSE 把 LLM 的 token 流和 Agent 的工具事件流合并推给前端；system prompt（长期记忆）+ advisor 注入的窗口历史（短期记忆）+ 当前消息共同构成模型看到的完整上下文；而 Tool Calling 让模型从「聊天机器人」变成「能操作业务系统的 Agent」——所有这些都建立在 Spring AI 的标准扩展点（Advisor / ChatMemoryRepository SPI / @Tool）上，没有自造轮子。

---

## 参考代码

- `backend/src/main/java/com/esmile/axis/chat/AgentController.java` — SSE 端点（传输层：DTO 校验 + ChatEvent → SSE 帧映射）
- `backend/src/main/java/com/esmile/axis/chat/AgentService.java` — 编排层：system prompt、Advisor 装配、双流合并
- `backend/src/main/java/com/esmile/axis/chat/ChatEvent.java` — sealed 事件类型（Token/Tool/Done）
- `backend/src/main/java/com/esmile/axis/chat/ChatRequest.java` — 请求 DTO（`@NotBlank` 校验）
- `backend/src/main/java/com/esmile/axis/chat/ToolCallNotifier.java` — 工具事件桥
- `backend/src/main/java/com/esmile/axis/ai/tool/` — 五个 Tool
- `backend/src/main/java/com/esmile/axis/chat/AiConfig.java` — ChatMemory Bean
- `backend/src/main/java/com/esmile/axis/chat/JpaChatMemoryRepository.java` — 短期记忆持久化
- `frontend/app/composables/useChat.ts` — 前端 SSE 消费与会话状态
- `docs/feature/chat-box/lite-spec.md` — 功能需求与验收标准
- `docs/tech-architecture/02-ai-config-api-key-lifecycle.md` — ChatClient 热切换与密钥生命周期
