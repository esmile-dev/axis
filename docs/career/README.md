# 求职包装：Axis 项目 → 大厂高级开发（偏 AI 方向）

目标岗位：大厂高级 Java 开发（AI 应用方向）。核心考察点是 **LLM 应用工程化能力**——Agent 设计、RAG 深度、流式架构、Prompt 工程、评测与降级、成本与可观测，外加 Java 后端硬功底。

## 文件索引

- [existing-features.md](./existing-features.md)：项目中**已有**的可包装 feature（9 个），每个附简历写法、背后技术、面试官深挖点
- [feature-roadmap.md](./feature-roadmap.md)：建议**补充**的 feature（P0/P1/P2 排序），附学习内容、实现要点、工作量，可勾选跟踪。**P0 四项已全部完成**（混合检索 / RAG 引用问答 / structured output / 确认门）；P1 已完成 #5 可观测性、#7 SSE 健壮性、#8 向量一致性，剩 #6 上下文压缩
- [interview-prep.md](./interview-prep.md)：学习路线图 + 面试叙事建议

## 项目定位（简历项目描述）

> **Axis —— 基于 Spring AI 的个人 AI 工作站（Agent + RAG）**
>
> 全栈 AI 应用：以 LLM Agent 为核心，通过 Tool Calling 操作 Inbox/待办/项目/知识库四大领域，删除类危险操作经 Human-in-the-loop 确认门放行；知识库基于 pgvector 实现混合检索 RAG（自研 token 级分块器 + 向量/关键词 RRF 融合 + 带引用的两段式问答）；内置 RSS 每日技术摘要生成管线（LLM 精读 + 主编润色，输出走 structured output 契约）；全链路流式 SSE 交互。

**技术栈**：Spring Boot 4 / Spring AI 2.0 / Java 21 / PostgreSQL 18 + pgvector / Nuxt 4 + Vue 3

## 叙事基调（重要）

- **如实说明是个人项目**。资深面试官一眼看穿假项目；有真实工程深度的个人项目反而是加分项——证明在没有业务压力时依然做了降级、幂等、评测这些"无趣但正确"的事。
- 叙事主线："工作中 AI 落地有限，所以用个人项目把 Spring AI 生态完整实践了一遍，以下是我在里面解决的真问题。"
- 每个 feature 准备"问题 → 方案 → 权衡 → 结果"四段式，有数字的结果最硬。
- 主动暴露 1~2 个已知局限 + 改进计划，把面试节奏控制在准备好的领域。
