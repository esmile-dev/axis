---
status: verified       # draft → approved → verified
feature: llm-observability
created: 2026-08-17
---

# 精简规格：LLM 可观测性 —— 调用日志 + Token 成本追踪（S~M 级）

## 1. 需求

背景：LLM 调用已收口 `ChatGateway` 单点（AgentService 两处例外），但没有任何调用记录——回答不了"线上 LLM 花了多少钱、慢在哪、谁在调用"。原 `llm_call_count` 只是 `digest_execution_log` 上一个手维护的计数器（Digest 预算用），不构成可观测体系。

目标（面试标准答案的三层）：

| 层 | 内容 | 验收 |
|----|------|------|
| L0 事实日志 | 每次 LLM 调用记录 feature / model / prompt / completion tokens / 耗时 / 成败，落 `llm_call_log` 表 | 真实 agent 对话后库内有行，token 非空 |
| L1 用量面板 | `GET /api/v1/llm/usage` 聚合 today/7d/30d + 按 feature 分组；Settings 页展示 | 接口返回正确聚合；页面可见 |
| L2 指标 | 自定义 Micrometer 指标（`llm.calls` / `llm.call.duration` / `llm.tokens`）+ Spring AI 内建模型层观测（`gen_ai.*`），暴露 `/actuator/metrics` | 两个指标族都可查 |

已与用户对齐的决策：三层全做；AgentService 旁路（chat/chatSync/expandPrd）接入，一轮用户消息记一条（tool loop 内部往返不逐次计）。

## 2. 方案与数据流

```
调用方（5 个经 ChatGateway + 3 个 AgentService 直连）
  → LlmCallTracker = llmCallLogger.start(feature)     # 发起前，记录起始时间
  → 流式途中 tracker.captureUsage(帧 metadata.usage)   # 保留最后一个非 null
  → tracker.success() / tracker.error(e)              # 结束/异常
  → LlmCallLogger.record(...)  @Async llmCallLogExecutor（单线程有界队列，队列满丢弃）
      ├→ repository.save(llm_call_log 行)
      └→ MeterRegistry：llm.calls / llm.call.duration / llm.tokens（tags: feature/model/status）

模型层（L2 内建）：AiConfigService.buildClient 把 ObservationRegistry 传进手动构造的
OpenAiChatModel → Spring AI 自动产出 gen_ai.client.operation / gen_ai.client.token.usage。
```

- **feature 维度**（`LlmFeature` 枚举）是 `LlmOptions` 的强制字段——新调用点编译期必须标注业务来源，这是相比"只记 token"的核心设计：能回答"成本花在哪个功能上"。
- **成本估算**：`ModelPricing` 内置已知模型刊例价（USD/1M tokens，精确名 + 最长前缀匹配）；未知模型不计入，窗口无已知模型时 `costUsd = null`——**未知 ≠ 0**。

## 3. 代码地图

`backend/axis-service/src/main/java/com/esmile/axis/llm/`：

| 文件 | 职责 |
|------|------|
| `LlmFeature.java` | 8 个业务来源枚举（AGENT_CHAT / PRD_EXPAND / KNOWLEDGE_QA / KNOWLEDGE_ASK / RERANK / DIGEST_SUMMARY / TITLE_GEN / ARTIFACT_GEN） |
| `LlmCallLog.java` + `LlmCallLogRepository.java` | 事实表 entity / 取数（30 天明细内存聚合） |
| `LlmCallLogger.java` | 唯一记录点：异步落库 + 自定义指标；任何异常静默（可观测性不拖垮主链路） |
| `LlmCallTracker.java` | 单次调用句柄：起始时间 + 末帧 usage 捕获；非线程安全，不跨请求共享 |
| `LlmObservabilityConfig.java` | `llmCallLogExecutor`：单线程 + 200 队列 + DiscardPolicy |
| `LlmUsageService.java` | `summarize(logs, now)` 纯函数聚合：三窗口 + byFeature + 成本 |
| `ModelPricing.java` | 刊例价表（gpt-4o / 4o-mini / 4.1 系列），接新模型在此登记 |
| `LlmUsageController.java` | `GET /api/v1/llm/usage` |

接入点改动：

- `config/ChatGateway.java`：`LlmOptions` 加 `feature`；`call/callEntity` 改取 `chatResponse()/responseEntity()` 拿 usage；`stream` 改 `.chatResponse()` 流，对调用方保持 `Flux<String>` 签名不变
- `axis-agent/.../ai/AgentService.java`：chat / chatSync / expandPrd 三处同样模式（`.doOnError(tracker::error)` 必须放在 `onErrorResume` 之前，否则错误被 error 帧吞掉后记录不到）
- `config/AiConfigService.java`：`buildClient` 传 `observationRegistry`（手动构造的 model 默认 NOOP，不传则内建观测丢失）
- `application.yml`：actuator 暴露加 `metrics`
- `db/migration/V1__init.sql`：`llm_call_log` 表（惯例：改 V1 重建本地库）
- 前端 `app/pages/settings.vue`：LLM 用量 section（三窗口卡片 + 按功能表）

## 4. 关键设计点（面试深挖）

1. **流式 usage 只在末帧**：读 Spring AI 2.0.0 源码证实 `OpenAiChatModel` 流式请求默认 `streamOptions.includeUsage(true)`（OpenAiChatModel.java:840），最后一帧只带 usage、choices 为空 → `getResult()` 为 null 必须滤掉，否则前端收到空 token。
2. **tool loop 记一次 vs 模型层逐次**：agent 一轮消息可能多次内部 LLM 往返，stream 末帧 usage 只反映最后一轮——`llm_call_log` 是**会话级**事实；逐次模型调用由 `gen_ai.client.token.usage` 精确覆盖。两层互补，如实标注语义差异。
3. **异步 + 静默失败**：记录线程队列满 DiscardPolicy 丢日志可接受，阻塞主链路不可接受；`record` catch-all 只 WARN。
4. **取消不产生记录**：SSE 断连（cancel）既不 complete 也不 error，无日志行——`LlmCallStatus` 因此只有 SUCCESS/ERROR。
5. **异常路径双重保障**：同步方法 try/catch 记 error 后原样抛出（不做重试，维持 ChatGateway 既有契约）；流式 `doOnError` 在 `onErrorResume` 之前。

## 5. 验证

- 单测：`LlmCallLoggerTest` 6 + `LlmUsageServiceTest` 7 + `ChatGatewayTest` 13（新增记录行为 4）+ `AgentServiceTest` 5（新增 2）；全量 `mvn test` 绿，`npm run build` 过
- 实测（2026-08-17，deepseek-v4-flash）：一次 agent 对话 → `llm_call_log` 两行（AGENT_CHAT 2293+63 tokens / 1719ms，新会话触发 TITLE_GEN 141+148 / 2780ms）；`/api/v1/llm/usage` 聚合正确；`/actuator/metrics/gen_ai.client.token.usage` 总计 5290 与两行日志 token 总和吻合（两层交叉验证）
- 实测（2026-08-19，kimi k3）：`ModelPricing` 登记 k3 后 `costUsd` 由 null 变为 $0.0068（4284 入 + 108 出）；未登记的 deepseek-v4-flash 行不计入成本但计入次数——"未知 ≠ 0"语义实测成立

## 6. 已知局限

- embedding 调用未纳入（独立 EmbeddingModel，未挂观测）
- 刊例价手工维护，新模型/调价需改 `ModelPricing`；当前使用的 deepseek-v4-flash 未登记，成本显示 "—"
- 流式中途取消不留记录；provider 不返回 usage 时 token 列留空（如实，不外推）

---

## 附录 A：L0 六个类逐一详解

L0 的目标一句话：**每次 LLM 调用都在数据库里留下一条事实记录**——谁调的、哪个模型、多少 token、多快、成败。L1（面板）和 L2（指标）都建立在这层事实上。按"数据从哪来到哪去"的顺序：

### A.1 `LlmFeature`（枚举）——"谁调的"

8 个值（AGENT_CHAT / PRD_EXPAND / KNOWLEDGE_QA / KNOWLEDGE_ASK / RERANK / DIGEST_SUMMARY / TITLE_GEN / ARTIFACT_GEN），每个对应一个会调 LLM 的业务功能。它是 `ChatGateway.LlmOptions` 的**强制构造参数**：任何新代码要调 LLM，编译器逼着选业务来源，不存在"忘了标"。只记 token 总数没意义——月底想知道的是"钱花在 Agent 对话上还是 Digest 上"。这是把可观测性从"靠自觉"变成"靠类型系统"。

### A.2 `LlmCallLog`（JPA 实体）——"记什么"

一条记录 = 一次调用的事实：`feature / model / prompt_tokens / completion_tokens / total_tokens / duration_ms / status / error_message / created_at`。token 三字段可空——provider 不返回 usage 时如实留空不外推。`status` 只有 SUCCESS/ERROR 没有 CANCELLED：流式被客户端断开时既不走 complete 也不走 error 回调，干脆不记录。

### A.3 `LlmCallLogRepository`——"怎么存"

普通 `JpaRepository`，只加 `findByCreatedAtAfterOrderByCreatedAtDesc`（给 L1 取近 30 天明细）；L0 本身只用继承的 `save()`。

### A.4 `LlmCallTracker`——"一次调用的跟踪句柄"

一次调用的"计时器 + 暂存器"，三个方法：

- `captureUsage(usage)`：流式场景每帧调一次，只保留最后一个非 null（OpenAI 流式只在末帧返回 usage，中间帧全 null）
- `success()` / `error(e)`：算耗时（内部 `startNanos`），让 logger 落一条记录；error 的 message 截断 500 字符防爆字段

把"计调用"的使用成本降到三行：`start()` → `captureUsage()` → `success()/error()`，调用方不用关心表结构和指标。非线程安全，生命周期绑定单次请求。

### A.5 `LlmCallLogger`——"唯一记录点"

所有记录汇聚到 `record()`：从 `AiConfigService.getConfig()` 取当前生效模型名 → 组装实体存库 → 顺手打 Micrometer 指标（L2 共用此记录点）。两个保护：

- `@Async("llmCallLogExecutor")`：落库在独立线程，SSE 主链路不等磁盘
- 方法体整体 try/catch，失败只 WARN：**可观测性永远不能拖垮业务**，DB 挂了聊天也得能用

### A.6 `LlmObservabilityConfig`——"记录用的线程池"

单线程 + 200 容量队列 + `DiscardPolicy`（满了丢日志也不阻塞）。个人项目日调用量百级，绰绰有余；真到队列满的程度，丢日志比卡聊天是对的取舍。

### A.7 串起来：一次真实调用的完整旅程

以实测"用一句话介绍你自己"为例：

1. `AgentService.chat()` 发起前 `llmCallLogger.start(AGENT_CHAT)` 拿到 tracker
2. token 逐帧流回，每帧过 `doOnNext` → `captureUsage()`，中间帧 usage 为 null 被忽略
3. 末帧只带 usage 没有内容 → usage 被捕获（2293 in / 63 out），空内容帧被 `mapNotNull` 滤掉
4. 流正常结束 → `doOnComplete` → `tracker.success()` → 异步落库：`AGENT_CHAT | deepseek-v4-flash | 2293 | 63 | 1719ms | SUCCESS`
5. 新会话触发异步标题生成 → `ChatGateway.call()` → 又一行 `TITLE_GEN | 141 | 148 | 2780ms`

若中途 LLM 报错：`doOnError`（在 `onErrorResume` **之前**）→ `tracker.error(e)` → 落一行 ERROR 带原因。

## 附录 B：AgentService 接入设计详解

### B.1 为什么不复用 ChatGateway

AgentService 的对话需要三样 gateway 没有的东西：`.tools(...)` 挂 5 个领域 tool、`Flux.merge` 合并工具事件流、确认门挂线程。设计决策：**不把 gateway 撑大成万能入口，让 AgentService 用同一套 Tracker 模式手工接入**。三个入口：`chat()` / `chatSync()` → AGENT_CHAT，`expandPrd()` → PRD_EXPAND。

### B.2 流式接入：四个操作符各管一件事

```java
LlmCallTracker tracker = llmCallLogger.start(LlmFeature.AGENT_CHAT);
Flux<ChatEvent> tokenFlux = chatClient().prompt()
        ...
        .stream()
        .chatResponse()                          // ① 不换就丢 metadata
        .doOnNext(cr -> tracker.captureUsage(cr.getMetadata().getUsage()))  // ②
        .mapNotNull(cr -> cr.getResult() != null
                ? cr.getResult().getOutput().getText() : null)              // ③
        .<ChatEvent>map(ChatEvent.Token::new)
        .concatWith(Flux.just(new ChatEvent.Done()))
        .doOnComplete(tracker::success)          // ④ 位置是设计出来的
        .doOnError(tracker::error)               // ④ 同上
        .doFinally(...)                          // 清理（原有）
        .onErrorResume(...)                      // error 帧（原有，#7 加的）
```

- **① `.content()` → `.chatResponse()`**：`.content()` 内部就是 `chatResponse().mapNotNull(取 text)`，转换时把 metadata（usage 所在）扔了。要记 token 必须自己拿 `Flux<ChatResponse>`。
- **② usage 捕获**：每帧过 `captureUsage`，末帧（`include_usage` 汇总帧）的真实数字被留下。
- **③ filter 变成必须**：末帧 choices 为空 → `getResult()` 为 null → 必须滤掉，否则前端收到空 token 帧。原来 `.content()` 内部帮你做，自己接管就得自己写。
- **④ 操作符顺序是核心**：error 路径上，信号先过 `doOnError`（记 ERROR）→ `doFinally`（清理确认门）→ `onErrorResume` 转成 error 帧 + done 帧后流"正常"结束——但这个 complete 发生在 `onErrorResume` 下游，`doOnComplete` 在上游，**error 路径永远不会误记 SUCCESS**。若 `doOnError` 写在 `onErrorResume` 之后，错误被吞成 error 帧，永远记不到。顺序由测试 `chat_llmStreamFails_recordsErrorBeforeErrorFrame` 钉住。

正常路径：内容流完 → `concatWith` 追加 Done → `doOnComplete` 记 SUCCESS。**一次对话恰好一条记录**；cancel（点停止按钮）时 complete/error 都不触发，一条都不记。

### B.3 chatSync：同步路径

无流，try/catch 直写：`.call().chatResponse()` → 捕获 usage → success → 取 text；异常记 error 后**原样抛出**，不改变既有错误契约。非流式 response 必有 usage，不用逐帧捕获。

### B.4 为什么一轮对话只记一条

agent 一轮对话内部可能有多次模型往返（LLM 决定调 tool → 执行 → 喂回 → 继续生成），流末帧 usage 只反映最后一轮。分层语义：

- 业务层按"一次用户消息 = 一条记录"——回答"这次对话花了吗、多慢"
- 逐次模型调用的精确计数交给 L2 `gen_ai.*` 内建指标（每次往返都记）

两层互补不重复。实测交叉验证：无 tool 触发的一轮对话恰好两条日志，token 总和 5290 与 `gen_ai.client.token.usage` 总计精确相等。

### B.5 计时口径

`tracker` 在 `tokenFlux` 组装**之前** start——AGENT_CHAT 的 `duration_ms` 是"这轮对话端到端耗时"（含 tool 执行），不是纯 LLM 生成耗时。这是有意的：业务层关心端到端；纯模型耗时看 L2 的 `gen_ai.client.operation` timer。

---

## 附录 C：L1 用量聚合详解

L1 目标：把 `llm_call_log` 的事实行变成人能看的东西——今天/近 7 天/近 30 天各多少次调用、多少 token、多少钱、多慢、失败几次，以及"钱花在哪个功能上"。三块：`LlmUsageService`（聚合）、`ModelPricing`（成本）、`LlmUsageController`（接口）+ 前端面板。

### C.1 `LlmUsageService`：核心设计是纯函数

```java
public UsageView summary() {                          // 薄壳：有副作用的部分
    Instant now = Instant.now();
    List<LlmCallLog> logs = repository.findByCreatedAtAfter...(now.minus(30, DAYS));
    return summarize(logs, now);
}
UsageView summarize(List<LlmCallLog> logs, Instant now) { ... }  // 纯函数：所有逻辑
```

- **为什么拆纯函数**：窗口过滤、分组、求和、均价、成本全部不碰 DB/时钟，测试直接构造 `List<LlmCallLog>` 断言输出——8 个用例零 mock。易错逻辑全部落在可纯测的函数里，`summary()` 三行薄壳不需要测。
- **为什么内存聚合而非 SQL GROUP BY**：个人级数据量（30 天几百上千行），一次查询 + Stream 聚合，比 JPQL 聚合投影简单且可测。前提如实标注：十万级就该换 SQL 聚合——是取舍不是疏忽。
- **窗口语义**：`today` = 本地时区自然日 0 点起（"今天"是人的概念）；`last7Days/last30Days` = now 减 N 天的滚动窗口；`byFeature` 只统计 30 天窗口、按次数降序。
- 每窗口产出 `WindowUsage(calls, errors, promptTokens, completionTokens, avgDurationMs, costUsd)`；`avgDurationMs` 回答"慢在哪"。

### C.2 `ModelPricing`：成本的诚实语义

- 内置刊例价表（USD / 1M tokens）：gpt-4o / 4o-mini / 4.1 系列 + k3/kimi-k3（$3.00 入 / $15.00 出；缓存命中价更低，usage 不分缓存，估算偏上限）。
- **最长前缀匹配**：实际模型名常带日期后缀（`gpt-4o-mini-2024-07-18`）；取最长是因为 `gpt-4o` 是 `gpt-4o-mini` 的前缀，取最短会把 mini 错配成 4o 价（差 16 倍）。
- **未知 ≠ 0**：模型不在表 → 该条不计入；窗口无已知模型 → `costUsd = null`，面板显示 "—"。`$0.00` 会误以为"没花钱"，"—" 才是"我不知道"。
- 金额用 `BigDecimal.movePointLeft(6)`，不用 float/double（项目规范）。

### C.3 接口与面板

`GET /api/v1/llm/usage` 一行委托返回 `UsageView`（record 嵌套，Jackson 直接序列化，BigDecimal 出来是 JSON number）。前端 `settings.vue` 新增 section：`loadUsage()` 与 `loadProfiles()` 并行；三张窗口卡片（成本大字 + 次数 + 入/出 token，失败红色）+ 按功能表（`FEATURE_LABELS` 中文映射、`formatTokens` k 缩写、`formatCost` null→"—"、`formatDuration` ms/s）。

## 附录 D：L2 Micrometer 双层指标详解

L2 目标：L1 给人看，L2 给机器看——时序、可抓取、能接监控告警。分两层指标，是本 feature 最有叙事价值的设计。

### D.1 两层指标

**业务层自定义指标**（打在 `LlmCallLogger.recordMetrics`，与 L0 落库共用记录点）：

| 指标 | 类型 | tags | 回答什么 |
|------|------|------|----------|
| `llm.calls` | Counter | feature, model, status | 各功能调用量、失败率 |
| `llm.call.duration` | Timer | feature, model, status | 各功能端到端耗时（含 tool 执行） |
| `llm.tokens` | Counter | feature, model, direction | 各功能 token 消耗 |

**模型层内建指标**（Spring AI 自带，只做激活）：`gen_ai.client.operation`（Timer，每次模型调用纯耗时）、`gen_ai.client.token.usage`（Counter，逐次往返 input/output/total）。tag 走 OpenTelemetry 语义约定，接任何 APM 都对得上。

### D.2 为什么要两层

- **粒度不同**：一轮对话触发 3 次内部往返时，`llm.calls` 记 1 条、`gen_ai.*` 记 3 次。实测交叉验证：无 tool 一轮对话恰好两条日志，token 总和 5290 = `gen_ai.client.token.usage` 总计。
- **耗时口径不同**：`llm.call.duration` 端到端 vs `gen_ai.client.operation` 纯模型——"对话慢是模型慢还是 tool 慢"一比便知。
- **维度不同**：内建指标没有 `feature` tag——"Digest 每天花多少 token"只有业务层答得出。

一句话：**日志（L0）是可回溯明细，指标（L2）是预聚合时序；业务层看功能，模型层看模型**——logs + metrics 互补的标准答案。

### D.3 激活内建指标的关键一行

`AiConfigService.buildClient()`：`OpenAiChatModel.builder().observationRegistry(observationRegistry).build()`。Spring AI 内建观测挂在 model 上，builder 默认 `ObservationRegistry.NOOP`；starter 自动装配会帮你注入，但本项目为热切换手工 new 模型，观测注册表也得手工传——"手工管理"的隐性成本，不做 L2 发现不了。embedding 模型未挂（局限）。

### D.4 暴露与局限

`application.yml` 暴露加 `metrics` 后 `GET /actuator/metrics/{name}` 可查。局限：counter 进程内累计、重启清零，历史趋势需接 Prometheus（只需加 registry 依赖，代码不动——Micrometer 门面的意义）；tag 只用低基数枚举（feature/model/status），高基数值（如 sessionId）进 tag 会炸指标存储。
