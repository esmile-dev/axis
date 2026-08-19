# Axis 领域词汇表

Axis 是 AI 个人工作站，管理从灵感捕捉到需求落地的完整生命周期。本文件只定义领域语言，不含实现细节。

## 收集与摘要

**Inbox Item（灵感条目）**:
极速捕捉的碎片信息，在 Inbox 页管理。状态只有 `TODO` / `DONE`；类型分 `NOTE`（手动笔记）与 `DIGEST`（Daily Digest 文章）两类。
_Avoid_: 便签、备忘录

**Daily Digest（每日技术摘要）**:
按 cron 定时从 RSS 信源抓取文章、分类后写入 Inbox 的条目集合，一天一份，重跑幂等。
_Avoid_: 日报、每日推送

**Digest Category（摘要分类）**:
Digest 文章的四级分类：`AI_FRONTIER` / `TECH_INDUSTRY` / `FINANCE_TECH` / `OTHER`，AI 关键词命中即归第一级。

## 项目与任务

**Project（项目）**:
一组 Issue 的容器。状态：`PLANNING` / `ACTIVE` / `COMPLETED` / `ARCHIVED`。

**Issue（任务）**:
最小工作单元，可隶属某个 Project，也可独立存在。状态：`BACKLOG` / `TODO` / `IN_PROGRESS` / `DONE` / `CANCELLED`；优先级：`NONE` / `LOW` / `MEDIUM` / `HIGH` / `URGENT`；类型：`BUG` / `FEATURE` / `IMPROVEMENT`。
_Avoid_: 工单、ticket

**Todo（统一待办）**:
不隶属任何 Project 的独立 Issue 集合；Todo 页是它的专用视图，不是独立实体。
_Avoid_: 待办事项（易与 Inbox Item 的 `TODO` 状态混淆）

**Comment（评论）**:
Issue 下的讨论记录。

## 知识库

**Knowledge Item（知识条目）**:
知识库中沉淀的一份内容，类型六选一：`ARTICLE` / `BOOK` / `PODCAST` / `VIDEO` / `TUTORIAL` / `NOTE`。阅读状态：`UNREAD` / `READING` / `DONE` / `ARCHIVED`，带阅读进度。
_Avoid_: 文档、资料

**Knowledge Artifact（知识产物）**:
AI 为知识条目生成的衍生内容，每条目每类一份：`SUMMARY`（摘要）/ `MINDMAP`（思维导图）。生成状态：`PENDING` / `GENERATING` / `DONE` / `FAILED`。

**Hybrid Search（混合检索）**:
知识条目的检索方式：向量语义与关键词双泳道并行，RRF 融合排序，可选 LLM rerank。

**Citation（引用）**:
RAG 问答答案中标注的来源知识条目，可回溯到具体条目。
_Avoid_: 出处、参考文献

## AI Agent

**Conversation（会话）**:
/chat 页一次连续对话的上下文，id 由前端生成。短期记忆是会话内消息的滑动窗口（只存 USER/ASSISTANT）；超窗消息由 LLM 滚动压缩为会话摘要（存 `chat_conversation.summary`），随 system prompt 注入。

**Long Memory（长期记忆）**:
Agent 判断有价值后主动保存的跨会话事实，每次请求注入 system prompt（上限 50 条）。与短期记忆是两套独立机制。
_Avoid_: 永久记忆

**AI Config Profile（配置档案）**:
一套命名的 AI 接入配置（key / endpoint / model），按用途分 `CHAT` / `EMBEDDING` 两类，每类同时最多激活一个，运行时以激活档案为准、环境变量兜底。
_Avoid_: 账号、配置项

**Domain Tool（领域工具）**:
Agent 经 Spring AI Tool Calling 可调用的五个领域操作接口：Inbox / Issue / Project / Knowledge / Memory。
_Avoid_: 函数、插件

**ChatGateway**:
全应用调用 chat 模型的唯一入口 module：需要 LLM 生成（同步文本、流式、结构化输出）的代码都经由它发起调用，不再各自组装模型客户端调用链。
_Avoid_: LlmService、ChatClient 封装、手写 `aiConfigService.get().prompt()…` 链
