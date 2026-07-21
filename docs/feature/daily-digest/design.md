---
status: approved       # draft → approved（G2，2026-07-21 用户确认）
feature: daily-digest
implements: [FR-001, FR-002, FR-003, FR-004, FR-005, FR-006, FR-007]
created: 2026-07-21
---

# 技术设计：Daily Digest 每日技术摘要

## 1. 方案概述

复用现有 digest 链路（fetch 并行抓取 / KeywordClassifier / Scheduler 均不动），**输出端从「写 md 文件」改为「写 `inbox_item` 表」**；前端 Inbox 页把 digest 文章当普通条目展示。关键取舍：

1. **digest 文章与手动笔记共用 `inbox_item` 一张表**（`type` 字段区分），不建新表——列表/筛选/搜索/转 Issue 全部复用，代价是表加宽
2. **已读用独立 `read_at` 字段**，不复用 `TODO/DONE`——「看过」和「处理完」是两个维度
3. **失败可重跑**：仅 `COMPLETED` 阻塞当日重跑；重跑前按 `digest_date` 删除当日旧 digest 条目保证内容不重复。放弃旧设计的悲观锁——单实例下数据库唯一约束已是硬保证

## 2. 数据模型

```mermaid
erDiagram
    DIGEST_EXECUTION_LOG ||--o{ INBOX_ITEM : "digest_date 逻辑关联(非外键)"
    INBOX_ITEM {
        varchar30 id PK
        text content "NOTE=笔记正文; DIGEST=文章标题"
        varchar10 status "TODO | DONE"
        varchar10 type "NOTE | DIGEST，默认 NOTE（新增）"
        text summary "DIGEST 一句话摘要，可空（新增）"
        varchar512 link "原文链接，可空（新增）"
        varchar50 source_name "信源名，可空（新增）"
        varchar20 category "AI_FRONTIER 等，可空（新增）"
        timestamp published_at "文章发布时间，可空（新增）"
        timestamp read_at "null=未看（新增）"
        date digest_date "所属 digest 日，重跑清理用，可空（新增）"
        timestamp created_at
        timestamp updated_at
    }
    DIGEST_EXECUTION_LOG {
        varchar30 id PK
        date digest_date UK "每自然日一行"
        varchar20 status "PENDING | COMPLETED | FAILED"
        int article_count
        timestamp created_at
    }
```

新列由 `ddl-auto: update` 自动添加；`digest_execution_log` 已存在，不变。

## 3. 接口设计

| 方法 | 路径 | 说明 | 对应 FR |
|------|------|------|---------|
| POST | `/api/v1/digest/trigger` | 手动触发；响应 `{executed, message, articleCount}`（已有，不变） | FR-002, FR-003 |
| GET | `/api/inbox` | 列表；返回体新增 `type/summary/link/sourceName/category/publishedAt/readAt`（已有，扩字段） | FR-006 |
| PATCH | `/api/inbox/{id}` | body 增加可选 `read:true` → 置 `readAt=now`（幂等：已有 readAt 不覆盖）（已有，扩展） | FR-007 |
| — | `/api/v1/digest/latest`、`/recent` | **删除**（无落盘文件可读，前端同步移除调用） | — |

定时入口不变：`@Scheduled(cron = app.digest.cron)`，默认 10/12/14/20/22（FR-001）。

## 4. 核心流程

```mermaid
sequenceDiagram
    participant S as Scheduler / Inbox按钮
    participant C as DigestController
    participant D as DailyDigestService
    participant R as RssFetcher（7源并行）
    participant K as KeywordClassifier
    participant DB as PostgreSQL
    S->>C: POST /trigger（或 cron 触发）
    C->>D: trigger()
    D->>DB: 查当日 digest_execution_log
    alt 当日已 COMPLETED
        D-->>C: executed=false（FR-003 幂等）
    else 无记录 / PENDING / FAILED
        D->>DB: 按 digest_date 删当日旧 digest 条目（重跑清理）
        D->>DB: upsert log = PENDING
        D->>R: 并行抓取（单源 5s / 总 15s，单源失败返空）
        R-->>D: List~Article~
        D->>K: 逐篇分类（FR-005）
        K-->>D: Article + category
        D->>DB: 批量写 inbox_item（type=DIGEST, read_at=null）
        D->>DB: log = COMPLETED, article_count
        D-->>C: executed=true
    end
    C-->>S: 200 {executed, message, articleCount}
```

前端交互：Inbox 页头部加「生成今日摘要」按钮（触发中显示 loading，完成后刷新列表）；点击 digest 条目 → 右侧展示标题+摘要+原文链接，同时乐观置已读 + `PATCH {read:true}`。

## 5. 影响面

| 类型 | 位置 | 改动方式 |
|------|------|----------|
| 实体 | `entity/InboxItem.java`、新增 `enums/InboxItemType.java` | 扩 8 个字段 + 新枚举 |
| digest 输出 | `digest/service/DailyDigestService.java` | 输出由写文件改为写 `inbox_item`；重跑清理逻辑 |
| 删除 | `digest/store/DigestInboxWriter.java`、`DigestFileReader.java` | 落盘链路整体删除 |
| 复用不动 | `digest/fetch/*`、`classify/*`、`scheduler/*`、`store/DigestExecutionLog*` | — |
| REST | `axis-agent/digest/controller/DigestController.java` | 删 `/latest`、`/recent` |
| Inbox API | `controller/InboxController.java`、`service/InboxService.java` | PATCH 支持 `read:true` |
| 配置 | `application.yml` | 删 `inbox-dir`；`rss-sources` 换实测可用 7 家 |
| 前端页面 | `pages/index.vue` | 加触发按钮；移除 digest 摘要行；点击条目标记已读 |
| 前端组件 | `InboxItemCard.vue`、`InboxDetailPanel.vue` | digest 样式（来源/分类徽章 + 未读点）、digest 详情（标题+摘要+链接） |
| 前端组件 | `DigestViewer.vue` | 删除 |
| 文档 | `CLAUDE.md` Daily Digest 段 | 实现完成后同步更新 |

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-07-21 | 初稿 | G1 通过后编写 |
