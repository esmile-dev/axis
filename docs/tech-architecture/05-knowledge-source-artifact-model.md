---
title: 知识库架构：Source + Artifact 模型与归一化不变量
slug: knowledge-source-artifact-model
description: 内容消化型知识库的核心抽象——一份原文（Source）多种派生产物（Artifact）。讲清双表模型的三个取舍（产物为何独立成表、状态字段为何冗余在主表、摄取为何必须归一化为 Markdown），以及防腐层思维如何让新内容形态成为纯增量。
status: knowledge
tags: [architecture, data-modeling, knowledge-base, anti-corruption-layer, design-tradeoffs, interview]
created: 2026-08-04
---

# 知识库架构：Source + Artifact 模型与归一化不变量

> 表结构是业务认知的直接映射。本文讲清知识库 2.0 的核心抽象从何而来、双表模型的每个字段为什么在那个位置。

---

## 1. 业务认知决定模型形态

知识库不是笔记软件（人写内容），而是**内容消化系统**（内容进来，AI 帮你消化）。这类产品（NotebookLM / Readwise Reader）的行业共识抽象：

> **一份原文（Source），多种派生产物（Artifact）。** 原文是唯一真相来源；总结、脑图、未来的转录、闪卡，全部是从原文派生的、可重新生成的「视图」。

## 2. 双表模型

```
knowledge_item（原文 + 元数据）        knowledge_artifact（派生产物）
├─ id / type / title / content         ├─ id / item_id (FK, LAZY)
├─ source_url / file_path              ├─ kind: SUMMARY | MINDMAP
├─ status / progress                   ├─ content / model / error
├─ summary_status / mindmap_status     └─ (item_id + kind) 唯一
└─ tags（@ElementCollection 副表）
```

## 3. 三个关键取舍

### ① 产物独立成表，而非主表宽列

宽列方案（主表加 `summary`、`mindmap` 列）的问题：每行背着三个大字段，列表查询浪费；**每加一种产物（TRANSCRIPT/FLASHCARD）都要改主表**。独立表后：新产物类型 = 新 kind 枚举值，主表零改动——开放封闭原则在数据模型上的体现。代价是详情页一次 join，列表页不 join。

### ② 产物状态字段冗余在主表

`summary_status`/`mindmap_status` 放主表而非只放 artifact 表，因为**列表页每行都要显示两个产物的状态点**。状态若在 artifact 表，列表必须 join 两次或逐行查（N+1）。这是**用冗余换读取路径简单的读写不对称设计**：写时状态机维护主表状态列（内容在 artifact 表），读时列表零 join。

### ③ 三条摄取管线必须归一化为 Markdown content

整个系统最重要的不变量：**所有下游（总结/脑图/问答/embedding）只需要理解一种格式**。若 URL 存 HTML、PDF 存二进制、粘贴存文本，每个下游都要写三套解析。归一化后摄取侧随便扩展（播客只是多一个「音频→文本」归一化步骤），下游永远不动——**防腐层**：把外部世界的混乱挡在摄取层。

## 4. 衍生认知

- **content 直接进 TEXT 列成立**：PG TOAST 机制自动压缩+行外存储（详见 `04-postgresql-storage-internals.md`）；列表查询不 SELECT content（SummaryView 只有 11 个元数据字段），大字段代价为零
- **file_path 留原件**：文本提取有损，原件落盘供未来重解析——溯源意识
- **已知限制明说**：LLM 输入截断 ~100k 字符并标注，map-reduce 长文总结列为范围外（主场景是文章，成本不值）

## 5. 面试问答速记

**Q：知识库数据模型怎么设计的？**
A：Source+Artifact 双表。原文归一化为 Markdown 进主表，AI 产物独立成表带状态机。新内容形态和新产物类型都是纯增量——加播客只需一个转录摄取器 + 一个 TRANSCRIPT 枚举，主表和下游不动。

**Q：为什么产物不放在主表加列？**
A：宽列每次加产物都改主表，且三个大字段挤一行。独立表换来扩展性和独立的失败重试；代价仅详情页一次 join。

**Q：状态字段为什么冗余在主表？**
A：列表页要逐行展示产物状态，放 artifact 表会导致 join/N+1。冗余是用写时双维护换读时零 join 的读写不对称设计。

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-04 | 初稿 | 知识库 2.0 复盘系列第 1 讲沉淀 |
