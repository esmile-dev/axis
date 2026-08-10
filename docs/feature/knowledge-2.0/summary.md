---
status: done           # G2 验收通过 2026-08-04
feature: knowledge-2.0
created: 2026-08-04
---

# 复盘总结：知识库 2.0

## 做得好的 / 可复用经验

- **Source+Artifact 双表 + AFTER_COMMIT 事件驱动异步管线**：产物状态机（PENDING→GENERATING→DONE/FAILED）与摄取解耦，失败可单独重试；未来播客转录、flashcard 等新产物直接复用此骨架，不动主表
- **三层降级链**：启动探测（pg_extension）→ 装配兜底（DDL 失败 try/catch）→ 运行时降级（embedding 失败回落关键词），每步 WARN 可观测；AI 依赖外部服务的功能都该这么写
- **脑图 = Markdown 大纲**：一份产物同时喂 markmap 和文字大纲视图，评测可用纯脚本判定合法性，零 LLM 成本
- **评测设施模式**：golden jsonl 自包含 + 脚本判定（可证伪）+ LLM-judge 契约文档 + `Assumptions` 门控（无 key 自动跳过），T-006/T-011 两次验证有效
- **SDD 子代理开发流程**：12 任务串行 implementer→reviewer→fix loop，4 次修复轮全部一轮关闭；ledger 文件是扛上下文压缩/中断恢复的关键
- **审查驱动修复的真实价值**：脑图 scale-0、非原文 tab 进度恢复失效两处真实 UX 缺陷都是审查轮抓出来的，单测/build 全绿也盖不住

## 踩坑记录

- **Spring Boot 4 迁移坑三连**：①`server.error.*` → `spring.web.error.*`（旧键静默忽略）；②测试切片拆独立模块（`@DataJpaTest` 在 `spring-boot-data-jpa-test`，包名全变）；③`mvn spring-boot:run -pl axis-agent -am` 在父 pom 失效，改用 `mvn package` + `java -jar`；multipart 键名未迁（`spring.servlet.multipart` 在 spring-boot-servlet jar）——配置键名一律从 autoconfigure jar 的 configuration-metadata 实证，别猜
- **JPQL 参数为 null 时 PG 推断 bytea** 报 `lower(bytea)`：mock 单测全绿、curl 才抓到——SQL 层必须有真实库集成测试（`@DataJpaTest` + `Replace.NONE` 直连 PG，事务回滚零污染）
- **markmap 在 `display:none` 容器 fit() 得 scale 0** 且无法滚轮自愈：挂载时机必须满足「首次可见才挂载」不变量
- **`@CreationTimestamp` 在 flush 前为 null**：创建后要用时间戳的响应必须 `saveAndFlush`
- `.env` 的 `DB_USERNAME=postgres` 对 axis 库无权限（应用实际走默认 `alan`），环境文件与库权限有历史欠账
- 子代理执行两次 OAuth 网络抖动中断、一次 2h 超时：大任务收尾（验证+报告）要控制节奏，中断后 resume 续跑有效

## 衍生需求 / 后续优化

- backlog（tasks.md 已登记）：B-001 会话前缀过滤、B-002 createDocument 走管线、~~B-003 pgvector 装扩展后复验~~（2026-08-06 已复验）、~~B-004 档案热切换刷新 EmbeddingModel~~（2026-08-06 已修复）、B-005 渲染消毒（XSS）、B-006 SSRF 防护
- P3：播客/视频转录（类型枚举已预留，转录产物走 TRANSCRIPT artifact 即可接入现有管线）
- 相关推荐/自动关联（沿用 spike `knowledge-auto-linking` 结论，向量设施已就位）、高亮批注、超长文 map-reduce 总结
- 评测方法论：judge 换独立模型消除同模型自评偏倚
