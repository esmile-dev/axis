---
name: soft-delete
description: 评估 Axis 是否引入软删除/回收站。盘点全部实体的删除入口与级联清理，核查 Hibernate 7.2 @SoftDelete 官方行为，给出明确结论。
status: draft
---

# Spike: 软删除 / 回收站评估

## 1. 问题

项目当前所有删除都是物理 DELETE。是否存在误删无法恢复的风险？要不要引入软删除（`deleted_at` 标记 + 回收站）？

## 2. 现状盘点（以代码为准）

全库无任何软删除痕迹（`deleted_at` / `@SQLDelete` / `@SoftDelete` / `@SQLRestriction` grep 零命中）。V1 全部 FK 均无 `ON DELETE CASCADE`，级联全靠 JPA `cascade=ALL`（backend/axis-service/src/main/resources/db/migration/V1__init.sql:90,100,159,174）。

| 实体 | 删除入口 | 级联/派生清理 | DB 约束要点 |
|---|---|---|---|
| InboxItem | REST `DELETE /api/inbox/{id}`（InboxController.java:38 → InboxService.java:81-83 `deleteById`）；Agent `deleteInboxItem`（InboxTool.java:56-66，确认门）；digest 重跑物理批量删（DailyDigestService.java:113 `deleteByTypeAndDigestDate`）；转 Issue/Project 默认删原件（InboxConvertDialog.vue:55） | 无子表、无派生数据 | 无 FK 指向它；status 仅 TODO/DONE（V1:132） |
| Issue | REST（IssueController.java:47 → IssueService.java:87-89）；Agent `deleteIssue`（确认门，IssueTool.java:71-81） | `comments` @OneToMany cascade=ALL orphanRemoval（Issue.java:63）→ JPA 级联物理删 Comment；**attachment 指向的上传文件不清理**（FileUploadService 只有 upload） | fk_comment_issue 无 DB 级联（V1:100）；status CHECK 含 CANCELLED（V1:88） |
| Project | REST（ProjectController.java:35 → ProjectService.java:55-57）；Agent 无删除 tool | `issues` cascade=ALL orphanRemoval（Project.java:39）→ **删项目级联物理删全部 Issue + Comment** | status CHECK 含 ARCHIVED（V1:71） |
| Comment | 无独立入口，仅随 Issue 级联 | — | fk_comment_issue（V1:100） |
| KnowledgeItem | REST（KnowledgeController.java:67 → KnowledgeService.java:174-180） | 手动删 artifacts → 删 item（tags @ElementCollection 由 JPA 清理，KnowledgeItem.java:58-64）→ `removeItem` 删向量分块（VectorKnowledgeIndexService.java:58-72，异常吞掉不阻塞）；**filePath 导入文件不清理**（KnowledgeFileStorage 无 delete） | uk_knowledge_artifact(item_id,kind)（V1:172）；status CHECK 含 ARCHIVED（V1:151） |
| KnowledgeArtifact | 无独立入口，仅随 KnowledgeItem 删除 | — | 同上唯一约束 |
| ChatConversation / ChatMessage | REST `DELETE /api/agent/conversations/{id}`（ChatHistoryController.java:32 → ChatHistoryService.java:44-47：先删消息再删会话）；JpaChatMemoryRepository.java:75-78 同 | 代码手动两表顺序删 | **chat_message.conversation_id 无 FK**（V1:50-58），一致性全靠代码 |
| ChatMessage（内部） | 非用户入口：超窗压缩物理删最旧 30 条（ConversationSummaryService.java:77）；saveAll 每轮全量替换窗口（JpaChatMemoryRepository.java:54） | — | — |
| ChatLongMemory | REST `DELETE /api/agent/memories/{id}`（ChatHistoryController.java:42 → ChatHistoryService.java:62-64）；Agent `deleteMemory`（确认门，MemoryTool.java:46-56） | 无 | 无 |
| AiConfigProfile | REST（ConfigController.java:69 → AiConfigService.java:187-210） | 保护「唯一激活 CHAT 档案不可删」；删激活档自动提升最老同类档并 reload | 无 |
| ArticleSummaryCache / DigestExecutionLog / LlmCallLog | **均无删除入口**（append-only；cache 无 TTL） | — | uk_digest_date 兼作幂等锁（V1:111） |

前端确认现状：仅 Knowledge（knowledge/index.vue:217）、AI 配置（settings.vue:226）、Issue 详情页（issues/[id].vue:32）有确认；**Inbox 删除（index.vue:160）、Todo 删 Issue（todo.vue:69）、删 Project（projects/index.vue:71,157）、删会话/记忆（chat.vue:102,274）全部无确认、直接乐观删除且无撤销**。

## 3. 软删除要解决什么，本项目已被什么覆盖

- **Agent 误删**：3 个删除 tool 已接确认门（ConfirmationService.java:48-72：5 分钟超时 / 无活跃流 / 流中断一律按拒绝，fail-closed）。Agent 侧核心风险已覆盖。
- **「不要了但想留痕」**：Issue.CANCELLED（enums/IssueStatus.java）、KnowledgeItem.ARCHIVED、Project.ARCHIVED 三个状态枚举实质就是软删除雏形——多数「舍不得删」的场景该用它们而不是删除。
- **未被覆盖的真空**：UI 一键删除无确认无撤销（上表前端段），尤其 Inbox 灵感（不可再生）与 Project 级联删（一次删整棵子树）。

## 4. 技术事实核查（一手来源）

- `@SoftDelete`（Hibernate 6.4 引入，[6.4 CR1 公告](https://in.relation.to/2023/10/26/orm-64cr1/)）：javadoc 明确「Soft deletes handle "deletions" from a database table **by setting a column** in the table to indicate deletion」——即 DELETE 改写为 UPDATE。可挂 TYPE（整个继承树，列在 root table）或 FIELD/METHOD（`@ElementCollection`/`@ManyToMany` 集合表行）；策略 DELETED（默认 boolean）/ACTIVE/TIMESTAMP（7.0 新增，[What's New in 7.0](https://docs.hibernate.org/orm/7.0/whats-new/whats-new.html)）。来源：<https://docs.hibernate.org/orm/7.2/javadocs/org/hibernate/annotations/SoftDelete.html>
- **查询自动过滤**：User Guide §3.12「Hibernate supports the soft delete of entities, with the indicator column defined on the primary table」，另有 §3.12.5 Collection soft delete、§3.12.6 Package-level soft delete（<https://docs.hibernate.org/stable/orm/userguide/html_single/>）。底层机制同 `@SQLRestriction`：javadoc 明确 restriction「add to **the generated SQL** for entities or collections」「**always applied** and cannot be disabled」（<https://docs.hibernate.org/orm/7.2/javadocs/org/hibernate/annotations/SQLRestriction.html>）。Spring Data 派生查询「We create a query using JPQL」（[Spring Data JPA 官方文档](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)），经 Hibernate 生成 SQL → **findByXxx 自动带过滤，无需改 repository**。
- **派生删除**：Spring Data 文档明确派生 `deleteByXxx`「runs a query and then deletes the returned instances **one by one**」（同上 URL）→ 逐条走 remove → `@SoftDelete` 生效且级联/回调照常。
- **唯一约束冲突**：软删除行物理仍在，唯一索引照样占用该值——PostgreSQL 唯一约束是对「all the rows in the table」生效（<https://www.postgresql.org/docs/current/ddl-constraints.html>）。本项目冲突点：`digest_execution_log.uk_digest_date`（软删后当天无法重跑）、`uk_knowledge_artifact(item_id,kind)`（软删 item 后重建 artifact 撞键）。
- **FK 级联**：`ON DELETE CASCADE` 只在真正 DELETE 时触发，UPDATE 标记不触发（同 PostgreSQL 文档 FK 节）；JPA `cascade=ALL` 删父实体时对子实体逐个 remove，子实体若也挂 `@SoftDelete` 则跟着变 UPDATE，**没挂则仍物理删**——父子混用会产生半软半硬的中间态。

## 5. 引入成本（若做）

1. 查询过滤全局污染：`@SQLRestriction` 不可按查询关闭（javadoc 原文），要查「含已删」只能 native SQL 旁路；`KnowledgeItemRepository.search`、向量库一致性、digest 幂等路径都要回归验证。
2. §4 的唯一约束与级联语义问题。
3. 派生数据不跟着软删：向量分块、磁盘文件（其实现在就连物理删都没清文件，见 §6）、`llm_call_log` 等 append-only 表根本不该软删。
4. 回收站 = 新 UI + 还原路径（`@SoftDelete` 无内建 restore）+ 定期真空清理，对一个未上线个人工具是显著过度设计。

## 6. 结论

**不引入 schema 级软删除/回收站。** 依据：Agent 侧误删已被确认门覆盖；「留痕」需求已被 CANCELLED/ARCHIVED 状态枚举覆盖；剩余真空是「UI 一键误删」，而它的最小解不是软删除——前端 `useOptimistic` 本就乐观删除，**把 DELETE 请求延迟 5 秒发出 + toast 撤销**即可零后端改动覆盖 Inbox/Todo/Project/会话四个无确认入口（knowledge/settings 已有 confirm）。若未来真要回收站，只在 InboxItem、KnowledgeItem 两个「内容不可再生、派生链最长」的实体上加 `deletedAt` 列 + 查询条件（不用全局 `@SQLRestriction`，避免污染内部路径），其余实体维持物理删除。

## 7. 盘点中发现的意外问题（独立 bug 候选，与本 spike 解耦）

1. **文件泄漏**：`KnowledgeService.delete` 不删 `filePath` 对应磁盘文件（KnowledgeFileStorage 无 delete 方法）；Issue 删除/换附件也不清理 `attachment` 文件（FileUploadService 只有 upload）。uploads/ 单调增长。
2. **Project 删除破坏力与 UI 不匹配**：dropdown 一个无确认的「Delete」（projects/index.vue:157）实际级联物理删除全部 Issue + Comment（Project.java:39 + Issue.java:63）。
3. **chat_message 无 FK**（V1:50-58）：删会话靠代码记得先删消息；现有两条路径都做了，但无 DB 兜底，新路径易漏。
4. `article_summary_cache` 无 TTL/清理入口（V1:23-33），长期单调增长（量小，可接受）。
