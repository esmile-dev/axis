---
status: approved       # G1 通过 2026-08-03
feature: knowledge-2.0
created: 2026-08-03
---

# 任务清单：知识库 2.0

## 执行规则（AI 必读）

- 一次只做一个任务；完成 → 打勾 → 填 commit hash → 下一个
- 不做任务表之外的事（想顺手优化 → 提建议，不直接做）
- 验收点不通过 → 修到过为止；需要偏离已批准设计 → 停下，走 design.md 变更流程

## 任务表

| ✓ | 编号 | 任务内容 | 对应 FR | 验收点（怎么算完成） | Commit |
|---|------|----------|---------|----------------------|--------|
| ☑ | T-001 | 数据模型：`knowledge/` 包实体（KnowledgeItem/KnowledgeArtifact/枚举/tag）+ Repository；删除旧 Knowledge 四件套与旧表说明；KnowledgeTool 临时适配新模型（保证全模块编译通过，向量升级留 T-012） | FR-001 | `mvn compile` 过；启动后新表建成；旧类不再存在 | fc08813 |
| ☑ | T-002 | 基础 API：粘贴创建/列表筛选搜索/详情/PATCH/DELETE（DTO record + Bean Validation）+ Service 单测 | FR-001/005 | curl 全链路通；`mvn test -pl axis-service` 过 | 9c059f0 |
| ☑ | T-003 | URL 抓取管线（jsoup+flexmark，15s 超时，422 错误语义）+ 单测 | FR-002 | 真实 URL 抓取 content 干净；故障注入返回 422 | 6300732 |
| ☐ | T-004 | 文件导入（md/txt/pdf，pdfbox，≤20MB，存 UPLOAD_DIR/knowledge/）+ 单测 | FR-003 | 三类文件导入成功；超限/类型错误被拒 | |
| ☐ | T-005 | 异步生成管线：事件+@Async+两产物状态机+regenerate 端点+总结/脑图 prompt 初版 | FR-004, NFR-001 | 入库后产物自动 DONE；注入 LLM 故障 → FAILED → regenerate 恢复 | |
| ☐ | T-006 | 评测：`evals/summary-golden.jsonl`（≥10 篇）+ 脑图合法性校验 + 评测入口，跑出达标结果 | NFR-003/004 | 评测实测达阈值，结果填入验收记录 | |
| ☐ | T-007 | from-inbox 端点：URL/文字两分支 + inbox 标已读 + 单测 | FR-008 | 两类条目转入成功且原条目 readAt 非空 | |
| ☐ | T-008 | 前端骨架：`pages/knowledge/` 三栏布局 + 左导航筛选 + 列表 + 添加 Dialog（三 tab）+ ⌘K 命令 | FR-001/002/003/005 | 页面可用，筛选搜索添加全通；旧 knowledge.vue 删除 | |
| ☐ | T-009 | 详情三视图：原文渲染/总结/markmap 脑图（含大纲切换、骨架屏、失败重试） | FR-006 | 三 tab 正常；脑图可交互；失败态可重试 | |
| ☐ | T-010 | 状态切换 + 滚动进度自动记录与恢复 + 标签编辑 + Inbox「转入知识库」入口 | FR-007/008 | 状态/进度持久化；Inbox 转入全链路通 | |
| ☐ | T-011 | P2 问答：`/api/knowledge/{id}/chat` SSE + 详情页问答面板 + qa-golden 评测 | FR-009, NFR-005 | 流式问答可用、历史持久化；评测达标 | |
| ☐ | T-012 | P2 检索：pgvector 检测装配 + 分块 embedding + KnowledgeTool 重写 + 降级 | FR-010 | 语义检索命中；禁 embedding 降级可用有 WARN | |

## 验收记录（实现完成后填写，G2 用）

| 对应 FR | 结果 ✓/✗ | 验证方式（命令 / 请求响应 / 操作步骤） |
|---------|----------|----------------------------------------|
| | | |

<!-- AI/Agent 功能追加评测结果：评测集版本 / 指标 / 阈值 / 实测 / 失败样例 -->
