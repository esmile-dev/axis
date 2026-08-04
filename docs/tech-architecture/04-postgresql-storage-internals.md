---
title: PostgreSQL 存储内幕：堆表、TOAST 与 MySQL 的关键差异
slug: postgresql-storage-internals
description: 从「行存在哪」讲起，对比 InnoDB 聚簇索引与 PG 堆表两种组织方式，展开大字段处理（溢出页 vs TOAST）的触发机制与工程推论，讲清为什么 PG 的 B+ 树效率与行大小无关、二进制的存储边界为什么在消费路径而不在存储机制，并用 knowledge_item 表做逐字段实战分析。
status: knowledge
tags: [postgresql, mysql, innodb, toast, storage, b-plus-tree, database-internals, interview]
created: 2026-08-04
---

# PostgreSQL 存储内幕：堆表、TOAST 与 MySQL 的关键差异

> 由「数据在数据页」这个直觉出发，讲清两种引擎的行组织方式和大字段处理机制。每条差异都落到工程推论：这条机制改变了你写 SQL / 建表 / 放文件的哪个决策。

---

## 1. 根本分歧：行数据住在哪

### MySQL InnoDB：聚簇索引（叶子页 = 行）

主键 B+ 树的**叶子节点直接存放完整行**。按主键查就是走树；按二级索引查，二级索引叶子存的是**主键值**，要再回主键树取行（回表）。

推论：
- 表即索引，**行越胖，每页（16KB）装的行越少**，叶子页越多、树越高、buffer pool 有效密度越低
- 主键顺序写入友好（自增 ID），随机主键（UUID）导致页分裂——面试常考
- 「热点表别放大字段、别 SELECT *」的经验法则来自这里

### PostgreSQL：堆表 + 独立索引

行（tuple）无序地放在**堆页**（8KB）里；所有索引（含主键索引）都是独立结构，索引条目 = **索引键 + ctid**（行物理地址，(页号, 页内槽位)，仅几字节）。按任何索引查，都是先查索引拿 ctid，再回堆取行。

推论：
- **PG 的 B+ 树效率与行大小天然无关**——索引里根本没有行数据，行再胖索引条目不胖
- 行变胖真正伤的是**堆页密度**：顺序扫描要翻更多页 → 这正是 TOAST 要解决的问题（见 §3）
- 没有「聚簇」概念，也就没有页分裂写放大那一套，但换来了索引全要回堆的代价

> 旁注（另一个大话题，不在本文展开）：行变更上 PG 走 MVCC 追加新版本（行头带 xmin/xmax，旧版本靠 VACUUM 回收），InnoDB 走原地更新 + undo log 存旧版本。面试问「PG 和 MySQL 的区别」时，聚簇/堆 + MVCC 实现是两个并列的一级知识点。

## 2. 大字段处理：溢出页 vs TOAST

两种引擎都知道不能把大行硬塞进数据页，都给大行留了「行外存储」，但触发机制差别很大。

### InnoDB 溢出页（DYNAMIC 行格式，5.7 起默认）

- 触发：行太长（经验阈值 ≈ 半页，~8KB）时，**最长的变长列**被整体挪到溢出页（off-page）
- 主记录留 **20 字节指针**
- **不压缩**
- 效果：几 KB 的中等 TEXT 仍然留在聚簇索引叶子里——「 TEXT 让表变胖」在 MySQL 里依然成立

### PG TOAST（The Oversized-Attribute Storage Technique）

- 触发：字段值 > **~2KB**（页的四分之一）就启动，比 InnoDB 激进得多
- 第一步先 **pglz 压缩**——文本通常压掉一半以上，很多值压完就能留在行内
- 还塞不下：值切成 ~2KB 的块，挪进这张表自动附带的 **TOAST 副表**（`pg_toast.pg_toast_<oid>`，带 (chunk_id, chunk_seq) 索引），主行留 **18 字节指针**
- 读取惰性：不 SELECT 该列就**完全不碰** TOAST 块；SELECT 时才按指针取块、拼回、解压（detoast）

### 对比速查

| | InnoDB 溢出页 | PG TOAST |
|---|---|---|
| 触发阈值 | 行 ≈ 半页（~8KB）才溢出 | 值 > ~2KB 即启动 |
| 先压缩？ | 否 | 是（pglz） |
| 主行指针 | 20 字节 | 18 字节 |
| 行外位置 | 溢出页 | TOAST 副表（有索引） |
| 索引是否含行数据 | 聚簇索引叶子=行，照样胖 | 索引只存键+ctid，不胖 |
| 工程结论 | 中等文本也能拖慢聚簇索引 | 堆行保持小，扫描不碰大字段 |

一句话：**MySQL 怕的是 B+ 树（叶子=行），PG 怕的是堆扫描，TOAST 把堆行也保住了。**

## 3. 查询路径分析：同一个「别 SELECT 大字段」，两种引擎的不同理由

知识库 2.0 的列表接口不返回 `content`（`KnowledgeItemSummaryView` 只有 11 个元数据字段）。这条设计在两种引擎下都成立，但论据不同：

- **MySQL 视角**：content 在聚簇索引叶子里，SELECT content 意味着把每个叶子页的大字段都读出来，IO 与网络传输都爆炸；不查它，叶子页只过元数据，还能用覆盖索引（二级索引键+主键值就够了）完全避免回表
- **PG 视角**：content 已被 TOAST，堆行很小，列表查询走索引拿 ctid 回堆取小行即可；一旦 SELECT content，每行都要额外去 TOAST 副表拼块解压——**TOAST 让胖字段「存得住」，但「读得多」照样贵**

推论：TOAST 解决的是「存」的问题（堆页密度），不解决「读」的问题（detoast 是逐行成本）。所以纪律照旧：**列表不取大字段，详情按主键取一次**。

> 加分项：PG 在「索引能覆盖查询列 + visibility map 新鲜」时可以走 **index-only scan**，连回堆都省掉——这是 PG 里「只查索引列」的额外收益，MySQL 的对应概念是覆盖索引。

## 4. 二进制：BYTEA 也走 TOAST，边界在消费路径

常见误解：「二进制不能放数据库是因为没有行外存储」。错——`BYTEA` 和 TEXT 走同一套 TOAST 机制。真正的边界：

| | 文本（如 knowledge_item.content） | 二进制（音频/视频/图片） |
|---|---|---|
| 消费者 | SQL / LLM（按主键整取，低频） | HTTP 响应 / 播放器（流式、Range 请求、高带宽） |
| 放 DB 的真实代价 | ≈0 | 每字节都过 DB 进程与连接；`pg_dump` 体积爆炸且无法增量（文件可 rsync 增量）；vacuum/复制成本上涨；DB 存储单价远高于对象存储 |
| 正确姿势 | 进库（TEXT/JSONB） | 落盘/对象存储，库里只留路径（取件码） |

结论：**存储位置跟着消费路径走**，跟「数据库能不能存」无关。

## 5. 实战：knowledge_item 表逐字段分析

```
knowledge_item
├─ id          varchar(30)  PK（B+ 树索引，条目 = 键 + ctid，瘦）
├─ type/status summary_status/mindmap_status   枚举字符串，行内
├─ title       varchar(500) 行内（<2KB 阈值）
├─ content     text          → 几乎必然 TOAST（>2KB，先压后挪）
├─ source_url  varchar(2048) 可能行内可能 TOAST，按需
├─ progress    int           行内
└─ created_at/updated_at     行内
```

- 列表查询：`WHERE type=? AND status=? ORDER BY created_at`——全是小字段；**当前无二级索引**，JPQL 的 `LOWER(content) LIKE '%q%'` 是全表扫 + detoast，个人库（几百行）无所谓；面试要能说出数据量上来后怎么办（三元索引 → trigram `pg_trgm` GIN 索引，或上 tsvector 全文检索，见 §6）
- 详情查询：按 PK 回堆一次 + detoast 一次，成本可控
- artifact 表同理：`content`（总结/脑图）也走 TOAST，`(item_id, kind)` 唯一约束背后是棵瘦 B+ 树

## 6. 面试问答速记

**Q：PG 和 MySQL 存储上最大的区别？**
A：行组织方式——InnoDB 聚簇索引（B+ 树叶子=行）vs PG 堆表+独立索引（索引只存键+ctid）。直接推论：MySQL 行胖伤 B+ 树，PG 索引不受行大小影响。

**Q：大文本字段怎么处理？**
A：两家都有行外存储。InnoDB DYNAMIC 溢出页（≈半页触发、20 字节指针、不压缩）；PG TOAST（>2KB 触发、先 pglz 压缩、切块进副表、18 字节指针）。PG 更激进，堆行保持小。

**Q：TEXT 进表会不会拖慢查询？**
A：分引擎分查询。PG 里只要列表不 SELECT 该列，索引和堆扫描都不碰 TOAST 块，几乎无感；MySQL 里中等文本留在聚簇索引叶子，确实拖低页密度。所以无论如何：列表不取大字段、详情按主键取、模糊搜索别直接 LIKE 大字段（上 pg_trgm/全文索引）。

**Q：为什么二进制不放数据库？**
A：不是不能（BYTEA 也 TOAST），是消费路径不合适——二进制要流式/Range/高带宽，过 DB 进程传输既贵又慢，还拖垮备份复制；落盘或对象存储，库里留路径。

**Q：PG 没有聚簇索引，那按主键查会不会比 MySQL 慢？**
A：MySQL 聚簇查主键走一次树；PG 走主键索引拿 ctid 再回堆，多一次堆访问——理论上多一跳，实际有缓存且堆行小，差异可忽略。换来的是没有页分裂写放大、二级索引不用存主键值回表两次。

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-04 | 初稿 | 知识库 2.0 复盘衍生：content/file_path 设计讨论沉淀 |
