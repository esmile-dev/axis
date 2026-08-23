---
title: Spring MVC 与 WebClient / WebFlux 在同一项目中共存
slug: spring-mvc-and-webclient-coexistence
description: 从「MVC vs Flux 二选一」的旧印象出发，讲清 Spring 5+ 如何把反应式做成了「与 servlet 解耦的工具集」，为什么本项目同时引入 spring-boot-starter-web 与 spring-boot-starter-webflux，三种角色（MVC / WebClient / Flux 返回值）如何各司其职、互不冲突。
status: knowledge
tags: [spring-boot, spring-mvc, webflux, webclient, reactor, reactive, architecture]
created: 2026-07-22
---

# Spring MVC 与 WebClient / WebFlux 在同一项目中共存

> 由浅入深讲清楚：为什么一个项目里可以同时存在 Spring MVC（`spring-boot-starter-web`）和 WebClient / Flux（`spring-boot-starter-webflux`），它们分别负责什么，以及「全栈反应式」到底什么时候才值得做。

---

## 1. 起点：先把「事实」摆出来

打开 `backend/pom.xml`，能看到两个 starter 同时存在：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>          <!-- Spring MVC：Tomcat servlet 栈 -->
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>      <!-- Spring WebFlux：Reactor + WebClient -->
    <!-- Flux 流式响应 (SSE) 支持 -->
</dependency>
```

而 `WebClient` / `Flux` / `Mono` 出现在项目里的全部位置，只有三处：

| 文件 | 角色 |
|---|---|
| `backend/src/main/java/com/esmile/axis/digest/config/WebClientConfig.java` | 注册共享的 `WebClient.Builder` Bean（统一 User-Agent / 超时 / 2 MB 上限） |
| `backend/src/main/java/com/esmile/axis/digest/fetch/RssFetcher.java:27,40-50` | 出站 HTTP 客户端，去拉外部 RSS/Atom XML |
| `backend/src/main/java/com/esmile/axis/chat/AgentController.java:35,72` | Controller 方法直接返回 `Flux<String>`，给前端 SSE 流式推 AI 回复 |

所以真相是**三种角色混在一起**：

- **Spring MVC**：服务端，处理普通 HTTP 请求（`@RestController` 返回 JSON）
- **WebClient**：出站 HTTP 客户端（去拉别人家的接口）
- **`Flux<T>` 返回类型**：服务端 SSE 流式响应（边算边推给前端）

---

## 2. 过去的印象：「MVC vs Flux 二选一」

在 Spring 5.0 之前，这是真的。当时的栈是单选：

| 模式 | 服务端 | 客户端 | 启动器 |
|---|---|---|---|
| 传统 Servlet | Spring MVC + Tomcat | `RestTemplate` | `spring-web` |
| 反应式 | Spring WebFlux + Netty | `WebClient` | `spring-webflux` |

老版本里 `spring-webflux` 会强制把 Tomcat 换成 Netty，**整个 servlet API 都没了**——所以大家印象里 MVC 和 Flux 是水火不容的。

---

## 3. 转折点：Spring 5.0 把反应式拆成「工具集」

Spring 5.0（2017）做了一件关键事：把反应式从「服务端栈」降级成**与 servlet 解耦的基础设施**。

```
              ┌──────────────────────────────┐
              │  Reactor (Mono/Flux)         │  ← 反应式编程模型（独立 jar）
              └──────────────────────────────┘
                          ▲              ▲
                          │              │
            ┌─────────────┴──┐   ┌──────┴────────────────┐
            │ 反应式 Web 栈   │   │ 反应式 Client / SSE    │
            │ WebFlux+Netty  │   │ WebClient / @RestCtrl │
            └────────────────┘   │ 返回 Flux<T>          │
                                 └───────────────────────┘
```

关键结论：

1. 你可以**只用 `WebClient`**（HTTP 客户端），不动服务端
2. 你可以**只用 `Flux<T>` 当 Controller 返回类型**（SSE），依然跑在 Tomcat 上
3. 真正的「全栈响应式」才需要把 servlet 栈也换成 Netty

---

## 4. Spring Boot 的「自动协商」规则

classpath 里同时出现 `spring-web` 和 `spring-webflux` 时，Spring Boot 怎么决定？

```
classpath 里有 spring-webflux 但没有 spring-web  → 启用 Netty（响应式 Web 栈）
classpath 里有 spring-web（无论有没有 webflux） → 启用 Tomcat（servlet 栈，默认）
```

**只要 classpath 有 `spring-web`，服务端就走 servlet 模型**——`spring-webflux` 只是被当作「反应式工具库」加载进来。

所以本项目运行时的真实情况：

| 角色 | 实际栈 |
|---|---|
| HTTP 服务端 | **Tomcat**（servlet，由 `spring-web` 决定） |
| 普通 `@RestController` | Spring MVC 同步处理 |
| 返回 `Flux<T>` 的 Controller | MVC 适配器用 `ReactiveHttpOutputMessage` 序列化，**Tomcat 依然是 servlet 线程** |
| 出站 HTTP（抓 RSS） | `WebClient` + Reactor Netty 客户端 |
| SSE 推送 | MVC 把 `Flux<String>` 写成 `text/event-stream` 流 |

---

## 5. 为什么这个项目要这么混着用

### 5.1 MVC 负责「正常 CRUD」

`InboxController`、`IssueController`、`KnowledgeController` 都返回 `List<T>` / `ResponseEntity<T>`，用 JPA + servlet 线程写起来最顺。改全栈反应式会**牵动数据库层、事务、缓存、监控**——没必要。

### 5.2 WebClient 负责「出站 HTTP」

`RssFetcher.java:38-50` 的核心写法：

```java
public List<Article> fetch(RssSource source) {
    try {
        String xml = webClientBuilder.build()
                .get().uri(source.url())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(perSourceTimeout)
                .onErrorResume(e -> {
                    log.warn("RSS fetch failed for {} ({}): {}",
                            source.name(), source.url(), e.toString());
                    return Mono.empty();
                })
                .block();   // ← 关键：最后一步把流"折叠"回同步世界

        if (xml == null || xml.isBlank()) return List.of();
        return parseXml(source, xml);
    } catch (Exception e) {
        log.warn("RSS fetch threw for {} ({}): {}",
                source.name(), source.url(), e.toString());
        return List.of();
    }
}
```

选 `WebClient` 而不是 `RestTemplate` 的理由（在这个项目里**不是为了性能，而是 API 现代性**）：

- `RestTemplate` Spring 5 已进入**维护模式**，`WebClient` 是推荐替代
- `WebClient` 链式 API + `onErrorResume` 写错误处理更顺
- 底层用 Netty 客户端，连接复用比 `RestTemplate` 的 `HttpURLConnection` 好
- 未来要把 10+ 个 RSS 源并发抓取时，只需把 `.block()` 换成 `Mono.zip(...)`，**完全无侵入升级**

### 5.3 Flux<String> 负责「SSE 流式响应」

`AgentController.java:34-46`：

```java
@PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> chat(@RequestBody Map<String, String> body) {
    String message    = body.get("message");
    String sessionId  = body.getOrDefault("sessionId", "default-session");

    return chatClient.prompt()
            .user(message)
            .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .advisors(s -> s.param("chat_memory_conversation_id", sessionId))
            .tools(inboxTool, issueTool, projectTool, knowledgeTool)
            .stream()      // Spring AI 返回 Flux<ChatResponse>
            .content();     // 抽成 Flux<String>，每 chunk 推一次
}
```

为什么必须返回 `Flux<String>` 而不能用 `String`？因为 AI 的回复是**流式生成**（一个字一个字吐），前端要「打字机效果」。如果用 `String` 等全部生成完，前端要等好几秒才有反应。

SSE（`text/event-stream`）的 wire format：

```
HTTP/1.1 200 OK
Content-Type: text/event-stream

data: 你好

data: ，我是

data: AI 助手
```

服务器**保持连接打开**、边算边推。前端用 `EventSource` 或 `fetch + ReadableStream` 逐 chunk 接收（前端 `useApi.ts` 注释里也写了「流式 SSE 端点直接用 fetch + ReadableStream，绕过 $fetch」——前后端是对应设计）。

---

## 6. 深一层：反应式到底解决什么问题

很多人误以为「反应式 = 更快」。**不对**。反应式的核心是**用更少的线程处理更多的并发连接**。

### 6.1 阻塞模型（servlet）

```
请求到达 → Tomcat 拿一条线程 → 线程同步等 HTTP/DB → 线程被占住
请求到达 → 拿另一条线程 → 又被占住
...
200 个慢请求 → Tomcat 200 条线程打满 → 第 201 个请求排队
```

### 6.2 反应式模型（WebFlux / Netty）

```
请求到达 → EventLoop 线程发请求 → 线程立刻去做别的事
        → DB 回调回来 → 同一个 EventLoop 处理响应 → 推给订阅者
200 个慢请求 → 4-8 条 EventLoop 线程就能搞定（非阻塞 I/O）
```

但反应式**有代价**：CPU 密集、JPA 同步 API、阻塞 IO 都不能写在反应式链上，否则线程被卡住就退化成 servlet 了。这就是为什么 `RssFetcher.fetch()` 里 `WebClient` 拉完数据后**立刻 `.block()`**——后面要喂给 Rome XML 解析器（CPU 密集 + 阻塞），没必要把整条链都反应式化。

---

## 7. 更深一层：MVC + WebClient 混用 = 「反应式桥」

`RssFetcher.fetch()` 是「WebClient in an MVC world」的标准写法：

```java
String xml = webClientBuilder.build()
        .get().uri(source.url())
        .retrieve()
        .bodyToMono(String.class)
        .timeout(perSourceTimeout)
        .onErrorResume(e -> {           // 反应式错误处理
            log.warn(...);
            return Mono.empty();        // 空值兜底，不抛异常打断调度
        })
        .block();                       // 桥接回同步世界
// ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
// 这一行 .block() 把反应式流折叠成 String，
// 让调用方 DigestService 完全不需要懂反应式。
```

对比一下 `RestTemplate` 写同样逻辑会多 5-8 行 try/catch，且无法复用 `.timeout()` / `.onErrorResume()` 这种声明式操作。

**反模式提醒**：`.block()` 在「**桥接到同步世界**」的场景不是反模式；只有「**全栈反应式**」代码里滥用 `.block()` 才是反模式（会在 EventLoop 上制造阻塞点，把反应式退化成 servlet）。

---

## 8. 对照组：如果做成「全栈反应式」会怎样

把 RSS 抓取改成全反应式版：

```java
// 假想：DigestService 全栈反应式版
public Mono<List<Article>> fetchAll() {
    return Flux.fromIterable(sources)
        .flatMap(src ->
            webClient.get().uri(src.url())
                .retrieve().bodyToMono(String.class)
                .timeout(perSourceTimeout)
                .onErrorResume(e -> Mono.empty())
                .mapNotNull(xml -> parseXml(src, xml))    // ← 这里会出大事
        )
        .collectList();
}
```

复杂度的连锁反应：

- 整个方法返回 `Mono`，调用方也必须是反应式
- 数据库、JPA、事务都得改（`@Transactional` 在反应式里要用 `R2DBC`，或包到 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`）
- 解析 XML 的 Rome 是阻塞库，得包到 `Schedulers.boundedElastic()` 上
- **复杂度显著上升**，收益却是「几个 RSS 源并发抓取」——**不值得**。

---

## 9. 本项目最终的运行时拓扑

```
┌─────────────────────────────────────────────────────────────┐
│                  Axis 项目运行时（localhost:7789）            │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│   前端 Nuxt (localhost:7788)                                │
│       │                                                     │
│       │ HTTP 普通请求（JSON）                                │
│       │ fetch + ReadableStream（SSE）                       │
│       ▼                                                     │
│   ┌────────────────────────────────────────────┐            │
│   │  Spring Boot (Tomcat，servlet 栈)          │            │
│   │  ┌──────────────────────────────────┐      │            │
│   │  │  Spring MVC 控制器                │      │            │
│   │  │  • InboxController    → JSON     │      │            │
│   │  │  • IssueController    → JSON     │      │            │
│   │  │  • ProjectController  → JSON     │      │            │
│   │  │  • AgentController    → Flux<..> │◄──── SSE        │
│   │  │      (Spring AI stream() 适配)    │      │            │
│   │  └──────────────────────────────────┘      │            │
│   │  ┌──────────────────────────────────┐      │            │
│   │  │  WebClient（出站 HTTP 客户端）    │      │            │
│   │  │  • RssFetcher → 拉 RSS/Atom XML │      │            │
│   │  │  • .block() 桥接回同步世界        │      │            │
│   │  └──────────────────────────────────┘      │            │
│   │  ┌──────────────────────────────────┐      │            │
│   │  │  Spring Data JPA（阻塞）          │      │            │
│   │  │  • Inbox / Issue / Project 实体   │      │            │
│   │  └──────────────────────────────────┘      │            │
│   └────────────────────────────────────────────┘            │
│       │                            │                       │
│       ▼                            ▼                       │
│   PostgreSQL                外部 RSS 源                    │
│                             (HN / Blog / etc.)             │
└─────────────────────────────────────────────────────────────┘
```

---

## 10. 决策清单：什么时候选什么

| 场景 | 推荐方案 | 理由 |
|---|---|---|
| 普通 CRUD / 后台管理 / JPA 读写 | **Spring MVC**（`spring-boot-starter-web`） | 简单、同步、跟 JPA 天然契合 |
| 需要流式响应（SSE / WebSocket） | MVC + `Flux<T>` 返回值 | 不用换服务端栈，Tomcat 适配器原生支持 |
| 出站 HTTP 客户端（新版代码） | **`WebClient`** | `RestTemplate` 已维护模式，链式 API + 反应式错误处理更好用 |
| 网关 / 大量长连接（>10k） | **全栈 WebFlux + Netty + R2DBC** | servlet 线程模型扛不住，反应式才有意义 |
| 旧 `RestTemplate` 代码 | 继续用即可 | 已稳定，新代码再换 |

---

## 11. 一句话总结

> **Spring 5+ 把反应式拆成了与 servlet 解耦的工具集**，所以 `spring-boot-starter-web` 和 `spring-boot-starter-webflux` 可以共存——前者决定服务端栈，后者只是把 `WebClient`（出站客户端）和 `Flux<T>`（流式返回类型）这两个好东西带进项目。本项目就是这种「**MVC 服务端 + WebClient 出站 + SSE 流式**」的性价比最高组合：99% 的代码用熟悉的 servlet 模型写，1% 的出站和流式场景用反应式点缀。