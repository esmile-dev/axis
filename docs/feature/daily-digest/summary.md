---
status: done          # draft → done（写完即归档）
feature: daily-digest
created: 2026-07-21
---

# 复盘总结：Daily Digest 每日技术摘要

> G4 验收通过（2026-07-21），按工作流 §6 移至 `docs/archive/daily-digest/` 归档。

## 做得好的 / 可复用经验

1. **共用表 + 状态字段扩展** 的取舍跑通：digest 文章与手动笔记共用 `inbox_item` 一张表，靠 `type` 字段区分；已读用独立 `readAt` 字段而非复用 `status`。零架构迁移成本，列表/筛选/搜索/转 Issue 全部自动复用。
2. **状态机驱动可重跑**：仅 `COMPLETED` 阻塞当日；`PENDING/FAILED` + 重试时先 `deleteByTypeAndDigestDate` 清旧条目，避免重复；`distinct_links=总条目数` 验证无重复。唯一约束仍是兜底。
3. **L 级 + 工作流强制拆分** 让 12 天的工作收敛到 10 个 ≤30 分钟任务，commit 历史干净可回滚（每任务 1 个 commit）。

## 踩坑记录

1. **`ddl-auto: update` 加 `NOT NULL` 列遇存量行**：T-005 实测启动报 `column "type" contains null values`。Hibernate `update` 模式只会 `ADD COLUMN`，不会回填。修复：实体加 `@ColumnDefault("'NOTE'")`，Hibernate 才会同时写出 `DEFAULT 'NOTE'` 让 PostgreSQL 自动回填存量行。**后续给已有表加必填列，必须同时加 ColumnDefault。**
2. **任务依赖倒挂**：原 tasks 写 T-002 删 `DigestFileReader`、T-003 删 controller 端点——但 controller 还在引用 reader，顺序颠倒会编译断裂。已通过在 tasks 变更记录里说明执行顺序调整解决；**后续写 tasks 时应当场画一遍依赖图，而不是仅按文档生成顺序排。**
3. **测试覆盖率本项目约等于零**（`npm run test` No test files found）。本次前端改动靠 `vue-tsc --noEmit` + `npm run build` + 手动验证兜底；后续工作流是否要求强制 unit test 可考虑加入豁免清单。

## 衍生需求 / 后续优化

- **信源管理界面**（已在 requirements.md §3 标记为第二期）：第二期开工建议走 M 级 lite-spec。
- **LLM 智能摘要 + ML 分类器**：已预留 `Classifier` 接口，v2 接 Spring AI 时只需新增 `LlmClassifier` 实现类 + `@ConditionalOnProperty` 切换。
- **InfoQ 偶发 5s 超时**：当前单源隔离已兜底；如要解决可调 `timeout.per-source-seconds` 至 8s 或换用 InfoQ 中文 API。
- **Inbox 类型过滤**：UI 暂无 `type=NOTE / DIGEST` 筛选，第二期可加到现有 Status 筛选旁。

## 归档

移至 `docs/archive/daily-digest/` 即可（按 workflow.md §5）。