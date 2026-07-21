---
status: in-progress    # approved（G3，2026-07-21 用户确认）→ in-progress → verified（G4）
feature: daily-digest
created: 2026-07-21
---

# 任务清单：Daily Digest 每日技术摘要

## 执行规则（AI 必读）

- 一次只做一个任务；完成 → 打勾 → 填 commit hash → 下一个
- 不做任务表之外的事（想顺手优化 → 提建议，不直接做）
- 验收点不通过 → 修到过为止；需要偏离已批准设计 → 停下，走 design.md 变更流程

## 任务表

| ✓ | 编号 | 任务内容 | 对应 FR | 验收点（怎么算完成） | Commit |
|---|------|----------|---------|----------------------|--------|
| ☑ | T-001 | `InboxItem` 扩 8 字段（type/summary/link/sourceName/category/publishedAt/readAt/digestDate）+ 新增 `InboxItemType` 枚举（NOTE/DIGEST，默认 NOTE） | FR-006, FR-007 | `mvn compile` 通过；启动后 `inbox_item` 表出现新列 | 6909819 |
| ☐ | T-002 | `DailyDigestService` 输出改造：写 `inbox_item`（type=DIGEST、read_at=null）替代写文件；当日 PENDING/FAILED 可重跑，重跑前按 digestDate 删旧条目；删 `DigestInboxWriter`/`DigestFileReader`；`application.yml` 删 `inbox-dir`、`rss-sources` 换 7 家；`DigestProperties` 同步清理 | FR-001, FR-003, FR-004, FR-005, FR-006 | `mvn compile` 通过；仓库中无 inbox-dir / daily-*.md 引用残留 | |
| ☑ | T-003 | `DigestController` 删 `/latest`、`/recent` 端点 | FR-006 | `mvn compile` 通过；两端点返回 404 | b380ff8 |
| ☐ | T-004 | `InboxController`/`InboxService` 的 PATCH 支持 `read:true` → 置 `readAt=now`（已有不覆盖） | FR-007 | PATCH 后 `readAt` 非空；重复 PATCH 时间戳不变 | |
| ☐ | T-005 | 后端集成验证：启动 → 手动触发 → 库中 DIGEST 条目齐（含来源/分类/链接）→ 同日二次触发 `executed=false` → `read:true` 生效 | FR-001~007 | 上述步骤全部实测通过，结果填入验收记录 | |
| ☐ | T-006 | `pages/index.vue`：头部加「生成今日摘要」按钮（loading + 完成后刷新列表）；移除 digestSummary 逻辑与「今日新闻摘要」行、`DigestViewer` 引用；`InboxItem` interface 补新字段；点击 digest 条目时乐观置已读 + PATCH | FR-002, FR-006, FR-007 | `npm run build` 通过；无 DigestViewer 引用残留 | |
| ☐ | T-007 | `InboxItemCard.vue`：digest 条目样式——来源/分类徽章 + 未读圆点（已读消失） | FR-006, FR-007 | 页面实看：未读条目有点，点击后消失 | |
| ☐ | T-008 | `InboxDetailPanel.vue`：digest 条目展示标题 + 一句话摘要 + 原文链接（新窗口打开）；删除 `DigestViewer.vue` | FR-006 | 页面实看：右侧详情完整，链接可跳转 | |
| ☐ | T-009 | 前端集成验证：`npm run dev` 全流程手动过一遍（按钮触发 → 列表出现条目 → 点击已读 → 右侧详情）+ `npm run test` | FR-002, FR-006, FR-007 | 全流程无报错；测试通过 | |
| ☐ | T-010 | 更新 `CLAUDE.md` Daily Digest 段；填写本文验收记录；文档与代码同提交 | — | CLAUDE.md 描述与新行为一致 | |

## 验收记录（实现完成后填写，G4 用）

| 对应 FR | 结果 ✓/✗ | 验证方式（命令 / 请求响应 / 操作步骤） |
|---------|----------|----------------------------------------|
| | | |

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-07-21 | 初稿 | G2 通过后编写 |
| 2026-07-21 | 执行顺序调整：T-003 提前于 T-002 执行 | T-002 要删 `DigestFileReader`，而 `DigestController` 引用它；先删端点解除编译依赖，任务内容不变 |
