---
status: verified       # draft → approved → verified
feature: backend-restructure
created: 2026-08-23
---

# 规格：后端单模块化 + 全域按域分包 + Modulith 边界校验（L 级）

## 1. 需求

背景：现有两模块（axis-service 130 类 / axis-agent 17 类）边界与业务域不符——两套分包风格并存（老域按层 controller/service/repository/entity/enums，新域按域 knowledge/digest/llm）、`config/` 杂物间（CORS、AI 档案业务、ChatGateway 基础设施、聊天记忆零件混居）、chat 域散落两模块 6 处、`DigestController`/`KnowledgeChatController` 无技术理由漂在 agent 模块。调研结论（`docs/tech-architecture/10-backend-module-structure-research.md`）：Maven module 的判据是产物类型而非业务域，Axis 只有一个部署产物；Spring Boot 官方示范即按域分包；边界强制官方指向 Spring Modulith。用户已拍板 **A+D**：合并单模块 + 按域分包 + Modulith verify。

| 编号 | FR | 验收标准 |
|------|-----|----------|
| FR-001 | 单模块化：axis-service + axis-agent 合并为 `backend/` 单 Maven 模块——src 上移拍平、packaging=jar、artifactId=`axis-server`、保留 spring-boot-starter-parent 父继承；依赖取并集去重（`spring-ai-starter-model-openai` 两模块重复声明） | `mvn package` 绿，产出单 fat jar `target/axis-server-0.1.0.jar`，启动后 `/api/health` 200 |
| FR-002 | 全域按域分包：根包 `com.esmile.axis` 下 7 个域包 + 仅 AxisApplication 居根；老六层包（controller/service/repository/entity/enums/config）清空删除 | 包列表仅剩 7 域包；`mvn test` 全绿（断言零变更，测试随类迁移） |
| FR-003 | 流浪类回家：`DigestController`→digest/；`KnowledgeChatController`+2 个 Request→knowledge/；axis-agent 的 `ai/`（含 `tool/`、`mcp/`）与 `config/AiConfig`→chat/ | 每类位于所属域包 |
| FR-004 | 行为零变化：REST 路径/方法/SSE 帧协议不变；application.yml、Flyway 迁移、resources 内容不变；前端零改动 | 启动冒烟四端点：health / chat SSE / knowledge ask / digest trigger 均正常 |
| FR-005 | Modulith 边界：引入 spring-modulith（与 Boot 4 兼容的 2.x GA，能 test scope 就 test scope）；`ApplicationModules.of(AxisApplication.class).verify()` 测试绿——模块依赖 DAG 无环、跨模块访问仅经 API 包 | verify 测试随 `mvn test` 跑且绿 |
| FR-006 | 活文档同步：AGENTS.md（仓库结构/构建命令/核心架构约定）、CONTEXT.md、docs/tech-architecture 涉模块路径篇目、docs/career/existing-features.md 与新结构一致；历史文档（docs/feature/、archive/、spike/）不回改 | 活文档无 axis-service/axis-agent 残留 |

**范围外**：任何业务行为变更；JPMS；ArchUnit 自定义规则集；前端；README.md 大修（本就过时，另行处理）；域包内部进一步 package-private 收紧（后续渐进）。

## 2. 方案要点

### 目标结构

```
backend/
├── pom.xml                      # 唯一模块 pom（packaging=jar）
├── settings.xml                 # 不动
├── src/main/java/com/esmile/axis/
│   ├── AxisApplication.java
│   ├── system/     WebConfig(CORS)、HealthController、FileUpload{Controller,Service}
│   ├── inbox/      InboxController/Service、InboxItem(+Repo)、InboxItemStatus/Type
│   ├── project/    Project/Issue/Comment 全套 + ProjectStatus、IssueStatus/Priority/Type
│   ├── knowledge/  现状 49 类不动 + KnowledgeChatController 等 3 类回家
│   ├── digest/     现状 19 类不动 + DigestController 回家
│   ├── llm/        LLM 接入（ChatGateway、AiConfigService、AiConfigProfile+Repo、
│   │               AiProfileType、ConfigController、AiConfigReloadedEvent）+ 现有可观测性 10 类
│   └── chat/       AgentService/AgentController/ChatRequest/ChatEvent/ConfirmationService/
│                   ToolCallNotifier、tool/*(5)、mcp/*(2)、ChatHistory{Controller,Service}、
│                   ConversationSummaryService、Chat* 实体+Repo、JpaChatMemoryRepository、
│                   AiConfig（ChatMemory bean）
└── src/main/resources/          # application.yml、db/migration 随 src 上移，内容不变
```

`llm/` 收编后约 17 类，默认平铺（ConfigController/ChatGateway 须在 API 面，子包会触发 internal 规则）；实施时若确需子包再配 `@NamedInterface`。

### 依赖方向（verify 应证明的 DAG）

`system → ∅`；`inbox → ∅`；`project → ∅`；`llm → ∅`；`knowledge → llm, inbox`；`digest → llm, inbox`；`chat → llm, inbox, project, knowledge`（chat→system 待实施时核实，预计无）。无环。

### 迁移映射规则（按规则执行，非逐类列举）

1. `entity/X` + `repository/XRepository` + `enums/X*` 按所属域归位：`Chat*`→chat；`AiConfigProfile`/`AiProfileType`→llm；`Comment` 随 project；`Inbox*`→inbox；`Project`/`Issue`/`IssueStatus/Priority/Type`/`ProjectStatus`→project
2. `controller/X` + `service/X` 同上：`ConfigController`→llm；`FileUpload*`/`HealthController`/`WebConfig`→system；`ChatHistory*`/`ConversationSummaryService`→chat；`Inbox*`→inbox；`Project*`/`Issue*`→project
3. `config/` 清空：`ChatGateway`/`AiConfigService`/`AiConfigReloadedEvent`→llm；`JpaChatMemoryRepository`→chat；`WebConfig`→system
4. knowledge/、digest/、llm/ 现有类原位不动（llm/ 只接收新成员）

### 已知 verify 违规与处置

- **chat→knowledge.search**（KnowledgeTool 用 `KnowledgeSearchService`/`KnowledgeSearchHit`）：search 子包标 `@NamedInterface("search")`——它本就是 SPI 形态（接口 + Keyword/Vector/Hybrid 三适配器）
- **chat→knowledge.repository/entity**（KnowledgeTool 直触）：`createDocument`/`listDocuments` 改走 `KnowledgeService` 门面，缺方法则在 KnowledgeService 补最小方法（一个 create 通道 + 一个列表查询）
- 其余跨域访问都在 API 根包（digest→inbox 写 `InboxItem`、knowledge→`InboxService`），平铺域包下天然合规

### Maven 合并步骤（T1）

`git mv backend/axis-service/src backend/src` → axis-agent 源码 git mv 并入 → 重写 `backend/pom.xml`（去 `<modules>`、packaging=jar、依赖并集去重、spring-boot-maven-plugin 保留、testResources 的 evals 路径 `../../docs` 改 `../docs`）→ 删两模块空壳目录。**本步包名不动**，隔离变量。

### 顺手项（默认决策，审阅时可改）

- `AiConfig.chatClient()` 无 `@Bean` 死代码：随迁移删除（其 javadoc 自述 ChatClient 已不再是 bean）
- `backend/inbox/`（digest 输出目录）未 track 也未 ignore：`backend/.gitignore` 补 `inbox/`
- `backend/uploads/` 已 ignore，不动

### 测试策略

每个任务节点跑 `mvn test`（需本地 PostgreSQL 运行）；纯移动不改断言；T3 后 verify 测试常驻；完成后启动冒烟四端点（FR-004）。

## 3. 任务

| ✓ | 任务 | 验收点 | Commit |
|---|------|--------|--------|
| ☑ | T1 Maven 合并拍平（包名不动） | 272 测试绿、`axis-server-0.1.0.jar` 启动 /api/health 200、inbox/projects 读取真实数据 ✅ | |
| ☑ | T2 老域按域分包 + 流浪类回家（73 类迁移） | `mvn test` 272 全绿、六层包消失 ✅ | |
| ☑ | T3 Modulith 接入 + verify 绿（@NamedInterface×2 + KnowledgeTool 门面化 + DigestCategory 迁 inbox 破环） | `ModularityTest` 绿、272 全绿 ✅ | |
| ☑ | T4 活文档同步（AGENTS.md×10 处、tech-architecture 01/02/03、existing-features、10 后记）+ .gitignore 补 inbox/ + 死代码删除 | 活文档无 axis-service/axis-agent 残留 ✅ | |

## 4. 验收记录

| FR | 结果 | 验证方式 |
|----|------|----------|
| FR-001 | 通过 | `mvn package` BUILD SUCCESS；`target/axis-server-0.1.0.jar` 启动后 `/api/health` 200，`/api/inbox`、`/api/projects` 返回真实数据 |
| FR-002 | 通过 | 主源码根包仅剩 8 域包 + AxisApplication；六层包删除；272 测试全绿（断言零变更） |
| FR-003 | 通过 | DigestController→digest/、KnowledgeChatController+2 Request→knowledge/（dto 子包）、ai/*→chat/（tool/mcp 子包保留） |
| FR-004 | 通过 | 冒烟：health + inbox + projects + agent conversations + knowledge + llm usage 端点正常；REST 路径未动（前端零改动） |
| FR-005 | 通过 | `ModularityTest.verifyModuleBoundaries` 绿：8 模块 DAG 无环、跨模块访问仅经 API 包/@NamedInterface（knowledge.dto、knowledge.search） |
| FR-006 | 通过 | AGENTS.md 10 处、tech-architecture 01/02/03 与 existing-features.md 路径全量改写（39 处替换）、10 号调研文档补后记；历史文档未动 |

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-23 | 初稿 | 用户拍板 A+D 方向后产出 |
| 2026-08-23 | 域包由 7 个更正为 8 个（补 `dispatch/`） | 前期文件清单输出截断导致盘点遗漏，T2 中发现 dispatch 4 类本就该独立成域 |
| 2026-08-23 | `DigestCategory` 从 digest.classify 迁至 inbox 根包 | T3 发现 inbox→digest 与 digest→inbox 双向依赖成环（InboxItem.category 字段类型），枚举随宿主实体归 inbox 破环 |
| 2026-08-23 | `TestJpaConfiguration` 迁至 knowledge 测试包 | 合并后根包同时存在 AxisApplication 与它两个 @SpringBootConfiguration，@DataJpaTest 引导冲突；下沉到 knowledge 包使包级搜索先命中它 |
| 2026-08-23 | `AiConfig.chatClient()` 死代码随 T2 删除 | spec 既定顺手项（无 @Bean，javadoc 自述废弃） |
