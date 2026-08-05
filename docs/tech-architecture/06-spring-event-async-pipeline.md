---
title: Spring 事件驱动异步管线：AFTER_COMMIT、短事务与状态机
slug: spring-event-async-pipeline
description: 以知识库 AI 加工管线为例，讲清异步任务设计的四个真实问题——可见性竞态、长事务危害、失败语义、丢失更新——及其工程解法：@TransactionalEventListener(AFTER_COMMIT)、慢调用在事务外、失败落库为状态、事件机制让竞态结构性不可能，以及向 MQ/outbox 的演进路径。
status: knowledge
tags: [spring, async, transactional-event-listener, state-machine, concurrency, llm-pipeline, interview]
created: 2026-08-04
---

# Spring 事件驱动异步管线：AFTER_COMMIT、短事务与状态机

> 「异步」两个字下面藏着四个真实问题：可见性、事务边界、失败语义、并发竞态。本文用知识库生成管线（入库 → 异步生成总结/脑图）讲透每个问题的结构性解法。

---

## 1. 全景

```
POST /api/knowledge（一个 @Transactional 方法内）
  │  saveAndFlush(item)                    ← 行已写入但未提交
  │  publishEvent(KnowledgeItemCreatedEvent(itemId))
  │  return 201                            ← 用户立刻拿到响应
  ▼ （事务提交完成的瞬间）
@TransactionalEventListener(AFTER_COMMIT)
  │  委托 collaborator 的 @Async 方法（knowledgeTaskExecutor：core 2/max 4/queue 50）
  ▼
KnowledgeArtifactGenerator
  │  SUMMARY: PENDING→GENERATING→(LLM 60s)→DONE / FAILED
  │  MINDMAP: 同上（一个失败不影响另一个）
  ▼
前端详情页轮询 3s：GENERATING → DONE 即渲染产物
```

## 2. 决策①：为什么是事件 + AFTER_COMMIT，而不是创建方法里直接 @Async

直接 `@Async` 有两个致命伤：

- **可见性竞态**：异步线程用**另一个数据库连接**读数据。事务未提交时行对其他连接不可见——异步任务查不到刚创建的 item。靠「时序上大概率来得及」是赌博不是工程
- **回滚浪费**：事务回滚时异步任务已跑出去——花真金白银调 LLM，给根本不存在的条目生成了产物

`@TransactionalEventListener(phase = AFTER_COMMIT)` 把触发点钉在提交完成之后：行必然可见（解①），回滚则事件不触发（解②）。附带收益：三个摄取入口（粘贴/URL/文件）及未来新入口，发事件即接入加工，**生产者与消费者彻底解耦**。

## 3. 决策②：生成方法为什么不开事务

LLM 调用最长 60s。整个生成方法挂 `@Transactional` = **数据库连接和行锁被捏住 60s**，两个任务就能拖垮应用。

规则：**事务要短，慢调用（网络/LLM）必须在事务外**。实现：生成方法无事务，每次状态更新（`markStatus`）是独立的 findById+save 短事务，连接占用毫秒级。

## 4. 决策③：状态机与失败语义

- 两产物各自独立走 `PENDING→GENERATING→DONE/FAILED`：脑图失败不让总结陪葬，重试也能单独重试
- **失败是一等公民的状态，不是日志里的一行**：异步方法 catch 一切异常 → error 截断（900/1000 列宽）写入 `artifact.error` → FAILED，绝不上抛。失败对用户可见（红色态+原因+重试按钮）、可恢复（regenerate）、可审计（DB 有现场）
- **手动重试而非自动重试**：LLM 故障多持续（key 错/配额尽），自动重试只会烧钱放大故障
- `markStatus` **写前重读**：两产物状态列共享同一 item 行，不重读会用陈旧实体覆盖另一产物的并发更新
- `inflight` 集合防同一 item+kind 重复生成（CountDownLatch 真并发测试钉住）

## 5. 决策④：真实的丢失更新 bug 与结构性修复

regenerate 初版（非事务）:

```java
item.setSummaryStatus(GENERATING);
repository.save(item);              // 外层持有的是异步修改前加载的陈旧实体
generator.regenerateAsync(itemId);  // 异步跑完写 DONE
// 外层事务提交 → 陈旧状态整体回刷 → DONE 被覆盖回 GENERATING
```

经典**丢失更新**。修复不是加锁，而是与创建管线对齐同一模式：`regenerate` 改 `@Transactional`，方法内只置 GENERATING 保存，发布 `KnowledgeArtifactRegenerationEvent`，AFTER_COMMIT 后异步才启动——异步 findById 必然读到已提交状态，竞态在结构上不可能发生。

> 修并发 bug 的最高境界：让竞态在结构上不可能发生，而不是加锁赌时序。
> 同 bug 牵出的懒加载 500（视图在 session 关闭后访问 tags）也随事务边界理顺而自愈——很多「莫名其妙」的懒加载异常，根子都在事务边界。

## 6. 决策⑤：线程池为什么自定义

Spring 默认执行器要么每次新建线程（不可控），要么全应用共享（互相挤占）。`knowledgeTaskExecutor`（core 2/max 4/queue 50，线程名 `knowledge-`）带来：**隔离**（不抢其他业务线程）、**有界**（队列是背压不是无限堆积）、**可观测**（日志线程名一眼认出）。

## 7. 演进路径：单机 → 分布式

当前是单机事件总线，多实例下事件不跨进程。演进：**outbox 模式**——AFTER_COMMIT 后写 outbox 表，relay 投递到 MQ（Kafka/RocketMQ），消费者逻辑与状态机**完全不用改**。面试被追问时重点说清「方案边界在哪、演进时什么变什么不变」。

## 8. 面试问答速记

**Q：怎么做不阻塞主流程的异步任务？**
A：事件驱动 + `@TransactionalEventListener(AFTER_COMMIT)` + 自定义线程池。AFTER_COMMIT 解决可见性竞态与回滚浪费两个坑。

**Q：异步任务里怎么保证数据一致？**
A：慢调用在事务外、写库用短事务；讲丢失更新真实案例——陈旧实体回刷，用事件机制让竞态结构性不可能。

**Q：失败重试怎么设计？**
A：失败落库为状态（非日志）、手动重试（LLM 故障持续性强，自动重试放大损失）、异步方法绝不上抛异常。

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-04 | 初稿 | 知识库 2.0 复盘系列第 2 讲沉淀 |
