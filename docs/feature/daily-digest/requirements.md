# Daily Digest — 功能需求文档

> 状态：v1（初始实现）
> 模块分布：业务代码位于 `backend/axis-service`，启动装配位于 `backend/axis-agent`。

---

## 1. 概述

AI Station 的 **每日科技摘要（Daily Digest）** 是一个自动化的科技新闻聚合器。
它按照固定计划从中文科技媒体的 RSS 源拉取头条，将每篇文章按优先级分类到四个桶中，
并把带日期标记的 Markdown 摘要写入用户的 inbox 目录。

本功能提供两种触发入口——定时调度器和手动 HTTP 接口——最终都汇聚到同一个具备幂等性的服务上。

---

## 2. 功能需求

### FR1 — 触发方式

| 入口 | 节奏 | 实现 |
|---|---|---|
| 定时 | 每天 10:00、12:00、14:00、20:00、22:00（服务器时区） | `@Scheduled(cron = "${app.digest.cron}")` |
| 手动 | 通过 REST 按需触发 | `POST /api/v1/digest/trigger` |

明确**不支持** CLI / 控制台直接执行。

### FR2 — 幂等性

每个自然日（服务器 JVM 时区下的 `YYYY-MM-DD`），digest 最多执行**一次**。
后续触发统一返回：

```json
{ "executed": false, "message": "Today's digest already generated", "articleCount": 0 }
```

幂等性通过三层防御性约束共同保证：

1. **数据库唯一约束** 在 `digest_execution_log.digest_date` 列上——这是硬性保证。
   重复 INSERT 会抛出 `DataIntegrityViolationException`，服务将其转换为"已跳过"的结果。
2. **悲观行锁**（`SELECT … FOR UPDATE`），通过
   `DigestExecutionLogRepository#findByDigestDateForUpdate` 获取。
3. **应用层检查**——如果 SELECT 返回了行，根本不会走到 INSERT。

### FR3 — RSS 信源

默认中文科技媒体（通过 `app.digest.rss-sources` 配置）：

| 来源 | URL |
|---|---|
| 36氪 | `https://36kr.com/feed` |
| 机器之心 | `https://www.jiqizhixin.com/rss` |
| InfoQ | `https://feed.infoq.com/` |
| 虎嗅科技版 | `https://www.huxiu.com/rss/0.xml` |

四个信源通过 `CompletableFuture` 在专用线程池（`digestExecutor`）上**并行**抓取。

### FR4 — 分类（三级优先级 + 兜底）

| 优先级 | 分类 | 关键词示例 |
|---|---|---|
| T0（命中任意 AI 关键词即胜出） | `AI_FRONTIER` | LLM、GPT、multimodal、embodied、chip/GPU、PyTorch、JAX、alignment、大模型、ChatGPT、Sora、具身智能、算力 |
| T1 | `TECH_INDUSTRY` | cloud、database、DB、HarmonyOS、Android、iOS、kubernetes、microservice、distributed、frontend、鸿蒙、微服务 |
| T2 | `FINANCE_TECH` | NVIDIA、TSMC、BAT、earnings、财报、investment、市值、半导体、投融资 |
| 兜底 | `OTHER` | 监管政策、未匹配的其它内容 |

关键词列表由配置驱动（`app.digest.classifier.keywords.*`），对 `title + " " + summary`
做大小写不敏感的子串匹配。

### FR5 — 写入 Inbox 文件

- 路径：`${app.digest.inbox-dir}/daily-YYYY-MM-DD.md`（默认 `./inbox/`）。
- 通过临时文件 + `ATOMIC_MOVE` 重命名实现原子写入。
- 每节以 `## 📅 Daily Digest (YYYY-MM-DD)` 起头。
- 每个分类 5–8 条最热文章，按 `publishedAt DESC` 排序。
- 一句话摘要直接取自 RSS 的 `description`，去除 HTML 标签后截断到约 240 字符。
- 分类始终按优先级顺序渲染：AI_前沿 → 技术_产业 → 财经_科技视角 → 其他_简报。

### FR6 — 状态存储

状态通过 JPA 持久化到现有 PostgreSQL 数据库（`DigestExecutionLog` 实体）。
不引入 Redis 和 Caffeine——直接复用现有数据源。

表结构（v1 由 Hibernate `ddl-auto: update` 自动创建）：

```
digest_execution_log(
    id              varchar(30)  PK,
    digest_date     date         NOT NULL UNIQUE,
    status          varchar(20)  NOT NULL,    -- PENDING | COMPLETED | FAILED
    article_count   int          NOT NULL DEFAULT 0,
    created_at      timestamp    NOT NULL
)
```

**v1 取舍**：`spring.jpa.hibernate.ddl-auto` 当前从 `validate` 改为 `update`。
这样首次启动时 Hibernate 可以自动创建 `digest_execution_log` 表，无需单独的
Flyway/Liquibase 迁移。等引入正式迁移工具后，应回退到 `validate`，并通过 `V2__*.sql`
显式创建表。

---

## 3. 非功能需求

### 性能
- 单源超时：**5 秒**（`WebClient.get().retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(5))`）。
- 总耗时上限：**15 秒**（`CompletableFuture.allOf(...).orTimeout(15, SECONDS)`）。
- 并发模型：有界 `ThreadPoolTaskExecutor`（`corePoolSize=8`，`maxPoolSize=16`，`queueCapacity=16`）。

### 健壮性
- 单个信源失败（超时、解析错误、网络异常）**不会**阻塞其他信源。每个 future 都用
  `.handle((r, ex) -> r == null ? empty : r)` 包裹。
- HTTP 失败仅记录 warning 并为该信源返回空结果。

### 可扩展性
- `Classifier` 接口——未来可通过
  `@ConditionalOnProperty(name = "app.digest.classifier.impl", havingValue = "ml")`
  注入基于 ML 的实现。
- 当前存储后端是 JPA；后续可通过重新实现 `DigestExecutionLogRepository`（或整体替换
  `DigestExecutionLog` 实体）来增加 Redis / Caffeine 后端。

### 运维
- 同一 JVM 内运行四次定时（一条 `@Scheduled` cron 携带多个时间）。多实例部署安全：
  数据库唯一约束保证每天最多只有一次执行成功。

---

## 4. API 规范

### `POST /api/v1/digest/trigger`

立即触发一次 digest 生成。始终返回 **200 OK**。响应体中的 `executed` 字段表示
本次是否真的执行了工作。

**请求体**：无。

**响应（首次调用，当天未执行）：**
```json
{
  "executed": true,
  "message": "Digest generated successfully",
  "articleCount": 24
}
```

**响应（同日再次调用）：**
```json
{
  "executed": false,
  "message": "Today's digest already generated",
  "articleCount": 0
}
```

**响应（失败场景）：**
```json
{
  "executed": false,
  "message": "Digest generation failed: <reason>",
  "articleCount": 0
}
```

---

## 5. 数据模型

### `DigestExecutionLog`

```java
@Entity
@Table(
    name = "digest_execution_log",
    uniqueConstraints = @UniqueConstraint(name = "uk_digest_date", columnNames = "digest_date")
)
public class DigestExecutionLog {
    String id;                  // CUID，通过 @PrePersist 生成
    LocalDate digestDate;       // UNIQUE —— 每个自然日一行
    DigestExecutionStatus status;  // PENDING | COMPLETED | FAILED
    int articleCount;
    Instant createdAt;          // @CreationTimestamp
}
```

### `DigestCategory`（枚举）

```java
public enum DigestCategory { AI_FRONTIER, TECH_INDUSTRY, FINANCE_TECH, OTHER }
```

### `DigestExecutionStatus`（枚举）

```java
public enum DigestExecutionStatus { PENDING, COMPLETED, FAILED }
```

---

## 6. RSS 信源

### 默认信源列表

参见 FR3。四个都是公开的 RSS / Atom 源，通过 HTTPS 拉取。信源列表可通过
`app.digest.rss-sources` 配置——新增信源只需改配置。

### 解析方式

依赖库：**`com.rometools:rome:2.1.0`**（Java 生态中最主流的 RSS/Atom 解析器）。

每个 feed 拉取为原始 XML 后，通过 `SyndFeedInput.build(XmlReader)` 解析。
RSS 2.0 与 Atom 用同一套 API 统一处理。每个 `SyndEntry` 映射为
`Article(title, link, summary, sourceName, publishedAt, category=null)`。
`category` 字段由分类器在后续步骤赋值。

### 失败隔离

- HTTP 失败 → 该信源返回空列表（记 warning）。
- XML 解析失败 → 该信源返回空列表（记 warning）。
- 信源列表为空（配置错误）→ 输出空 digest，`articleCount=0`。

---

## 7. 分类器

### 接口

```java
public interface Classifier {
    DigestCategory classify(Article article);
}
```

### 默认实现：`KeywordClassifier`

构造时将关键词列表归一化为小写。基于 `text.toLowerCase().contains(keyword)`
对 `title + " " + summary` 做匹配。

优先级：`AI_FRONTIER` > `TECH_INDUSTRY` > `FINANCE_TECH` > `OTHER`。

### ML 升级预留

替换为 ONNX / 向量 / LLM 分类器的步骤：

1. 用新策略实现 `Classifier`。
2. 标注 `@ConditionalOnProperty(name = "app.digest.classifier.impl", havingValue = "ml")`。
3. 在现有的 `KeywordClassifier` 上加反向条件（`havingValue = "keyword"`）。
4. 在目标环境中设置 `app.digest.classifier.impl=ml`。

无需其他代码改动。

---

## 8. Inbox 文件格式

`./inbox/daily-2026-07-20.md`：

```markdown
## 📅 Daily Digest (2026-07-20)

> Generated at 14:00 by Daily Digest. 24 articles across 4 categories.

### AI_前沿 (8)
1. [Title](https://link) — 一句话摘要
2. ...

### 技术_产业 (6)
1. ...

### 财经_科技视角 (3)
1. ...

### 其他_简报 (7)
1. ...

---
```

- 每天一个文件，无需 prepend。
- 通过临时文件 + `Files.move(ATOMIC_MOVE)` 实现原子写入。
- 同日重复运行会**安全地覆盖**该文件。

---

## 9. 配置参考

所有配置位于 `application.yml` 的 `app.digest.*` 命名空间下。

| Key | 默认值 | 说明 |
|---|---|---|
| `cron` | `0 0 10,12,14,20,22 * * *` | Spring cron 表达式。每天 10:00、12:00、14:00、20:00、22:00 触发。 |
| `inbox-dir` | `${DIGEST_INBOX_DIR:./inbox}` | 存放 `daily-YYYY-MM-DD.md` 的目录。 |
| `rss-sources` | 4 个信源（见 FR3） | `{name, url}` 列表。 |
| `classifier.impl` | `keyword` | 当前仅发布 `keyword`，`ml` 留给未来。 |
| `classifier.keywords.ai-frontier` | `AI,LLM,GPT,...` | 逗号分隔的 T0 关键词。 |
| `classifier.keywords.tech-industry` | `cloud,database,...` | 逗号分隔的 T1 关键词。 |
| `classifier.keywords.finance-tech` | `NVIDIA,TSMC,...` | 逗号分隔的 T2 关键词。 |
| `timeout.per-source-seconds` | `5` | 单个 feed 的 HTTP 超时。 |
| `timeout.total-seconds` | `15` | 并行抓取的总时间上限。 |
| `thread-pool-size` | `8` | `digestExecutor` 的核心线程数。 |

---

## 10. 错误处理

| 场景 | 行为 |
|---|---|
| 信源抓取超时 | 记 warning，该信源返回 `[]`，其它信源继续执行。 |
| 信源 XML 损坏 | 记 warning，该信源返回 `[]`，其它信源继续执行。 |
| 全部信源失败 | 仍会写入 digest 文件，分类下显示 `_(no items)_` 占位。`articleCount=0`，状态 `COMPLETED`。 |
| 磁盘写入失败（权限、磁盘满） | 状态置为 `FAILED`，异常向上抛出，响应体报告失败。 |
| 同一分钟内两次触发 | 悲观锁 + 唯一约束：第二次能看到第一次的行，返回 `executed=false`。 |
| `@Scheduled` 与正在执行的手动触发重叠 | 同上：第二次能看到第一次的行（`PENDING`），返回 `executed=false`。第一次执行完成后将行更新为 `COMPLETED`。 |

---

## 11. 未来扩展点

### 基于 ML 的分类器
- 用 ONNX 或 LLM 版的 `Classifier` 实现替换 `KeywordClassifier`。通过 `@ConditionalOnProperty` 切换。

### Redis 幂等存储
- 增加 `RedisDigestExecutionLogRepository` 作为 JPA 实现的替代。通过
  `app.digest.execution-log.impl=redis` 切换。数据库唯一约束仍作为安全网保留。

### 多实例分布式锁
- 当并发运行 ≥2 个 JVM 时，用 `RedissonClient.getLock("digest:" + today)` 包住 `trigger()`。
  目前数据库唯一约束已经足够，因为写入本身是幂等的。

### 前端触发按钮
- 把现有的 `POST /api/v1/digest/trigger` 接到 inbox 页面上的一个按钮。（前端改动。）

### 智能摘要
- 用 LLM 生成的一行摘要替代 RSS 的 `description`。会引入 OpenAI 调用成本，待 v1 流量起来后再考虑。

### `GET /api/v1/digest/latest`
- 简易后续：读最新的 `daily-*.md` 文件，或按 `digest_date DESC LIMIT 1` 查询
  `DigestExecutionLog`。v1 不实现。