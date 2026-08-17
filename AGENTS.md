# AGENTS.md

本文件面向 AI 编码 Agent，提供本仓库的上下文与工作约定。阅读者默认对项目一无所知。

## 项目概览

**Axis（AI Station - 个人工作站）**：一个 AI 个人工作站，管理从灵感捕捉到需求落地的完整生命周期。核心模块：

- **Inbox（灵感回收站）**：极速收集碎片信息，`⌘K`/`Ctrl+K` 唤起全局命令面板
- **Todo（统一待办）**：轻量级行动清单
- **Projects / Issues**：项目与任务管理，支持拖拽流转
- **Knowledge Base（知识树）**：技术复盘、方法论沉淀
- **AI Agent**：通过 `/api/agent/chat`（SSE 流式）与 LLM 对话，Agent 经 Spring AI Tool Calling 自动调用 Inbox / Issue / Project / Knowledge / Memory 5 个领域 Tool 操作数据
- **Daily Digest（每日技术摘要）**：RSS 抓取 → 关键词分类 → 写入 `inbox_item`，详见下文

设计与交互风格参考 **Linear.app**：极简高效、键盘优先、暗色主题、骨架屏代替 spinner。

> 注意：根目录 `README.md` 内容已过时（单模块结构、端口 8080、`./mvnw` 等均与现状不符），以本文件和 `application.yml` 为准。

**项目阶段**：项目尚未上线，无线上数据和兼容性包袱——必要的重构（模块改名、表结构、API 变更等）都可以直接改，不留兼容层、不写迁移脚本。

## 技术栈

- **前端**：Nuxt 4 + Vue 3 Composition API + TypeScript；Pinia + VueUse 状态管理；Tailwind CSS + Shadcn-Vue（基于 Reka UI）+ Lucide Vue Next 图标；`marked` 渲染 Markdown
- **后端**：Spring Boot 4.0 + Spring Framework 7.0 + Java 21；Spring Data JPA + Hibernate；Spring AI 2.0（ChatClient + Tool Calling）；Maven 多模块构建
- **数据库**：PostgreSQL 18 + pgvector 扩展（本地默认 `axis` 库；Flyway 管 schema + `ddl-auto: validate`；开发期改表直接改 `V1__init.sql` 后重建本地库，不写增量迁移脚本；`vector_store` 表由 PgVectorStore 启动时自建）

## 仓库结构

```
frontend/                        # Nuxt 前端（端口 7788）
├── app/
│   ├── components/              # Vue 组件；ui/ 为 Shadcn-Vue 基础组件；CommandPalette.vue 为 ⌘K 面板
│   ├── composables/             # useApi / useLocalFirst / useOptimistic / useImageUpload / useChat
│   ├── layouts/  lib/  types/
│   └── pages/                   # index(Inbox) / todo / chat / projects/ / issues/[id] / knowledge / settings
├── nuxt.config.ts               # devServer.port=7788；runtimeConfig.public.apiBase 默认 http://localhost:7789
└── package.json

backend/                         # Maven 多模块（父 POM packaging=pom，无 Maven Wrapper，用系统 mvn）
├── pom.xml
├── inbox/                       # Daily Digest 输出目录（daily-YYYY-MM-DD.md）
├── axis-service/                # 业务核心模块（jar，无主类，不可独立启动）
│   └── src/main/java/com/esmile/axis/
│       ├── controller/          # REST API：Inbox/Project/Issue/Knowledge/ChatHistory/Config/FileUpload/Health
│       ├── service/  repository/  entity/  enums/
│       ├── digest/              # Daily Digest：fetch / classify / summarize / scheduler / service
│       └── config/              # CORS 等配置
├── axis-agent/                  # AI Agent 模块（依赖 axis-service，产出可执行 fat jar）
│   └── src/main/java/com/esmile/axis/
│       ├── AxisApplication.java # 唯一的 Spring Boot 主类（扫描整个 com.esmile.axis）
│       ├── config/AiConfig.java # ChatClient / ChatMemory 配置
│       ├── ai/                  # AgentController + ToolCallNotifier + tool/（Inbox/Issue/Project/Knowledge/Memory 五个 Tool）
│       └── digest/controller/   # /api/v1/digest REST 入口

docs/
├── agents/                      # Agent 配置：issue tracker（.scratch 约定）/ triage 标签 / domain docs 规则
├── PRD/  tech-architecture/  spike/  archive/   # archive/ 内含已归档的旧开发流程宪章 workflow.md
└── feature/                     # 历史功能文档（旧 Spec-Driven 流产物）；新规格统一落 `.scratch/<feature>/`
```

后端配置集中在 `backend/axis-service/src/main/resources/application.yml`（端口、数据源、AI、digest cron/RSS 源均支持环境变量覆盖）。

## 构建与运行命令

```bash
# 前端（端口 7788）
cd frontend && npm install
cd frontend && npm run dev          # 开发服务器
cd frontend && npm run build        # 生产构建
cd frontend && npm run preview      # 预览构建结果

# 后端（端口 7789）
cd backend && mvn package && java -jar axis-agent/target/axis-agent-0.1.0.jar   # 启动（Boot 4 下 spring-boot:run 在父 pom 聚合报错，走 fat jar）
cd backend && mvn compile           # 编译
cd backend && mvn package           # 打包可执行 fat jar
```

前置条件：本地 PostgreSQL 18 已运行且存在 `axis` 数据库，库内已启用 pgvector（`CREATE EXTENSION vector`；Homebrew 安装：`brew install postgresql@18 pgvector`）。

## 测试

```bash
# 前端
cd frontend && npm run test              # vitest run（当前仓库尚无前端测试文件）
cd frontend && npx vitest run <file>     # 跑单个测试文件

# 后端
cd backend && mvn test                                   # 全部测试
cd backend && mvn test -pl axis-service -Dtest=SomeTest  # 跑单个测试
```

后端测试位于 `backend/axis-service/src/test/java/...`（如 `DailyDigestServiceTest`、`AiConfigServiceTest`、`ConfigControllerTest`）。`mvn test` 前置依赖本地 PostgreSQL 运行（`KnowledgeItemRepositorySearchTest` 直连 `axis` 库）。`digest/summarize/SummarizationEval.java` 是 AI 功能的评测入口（AI 功能验收必须可评测，沿用已归档 `docs/archive/workflow.md` 第 7 条的原则）。

**提交前必须测试。未验证 = 未完成。**

## 核心架构约定

前后端分离：前端通过 `app/composables/useApi.ts`（基于 `runtimeConfig.public.apiBase`，可用环境变量 `AXIS_API_BASE` 覆盖）调用后端 REST API。

- **Local-First（首屏秒开）**：`useLocalFirst.ts` — 页面优先从 LocalStorage 读缓存立即渲染，后台静默同步数据库。所有使用此模式的页面必须调用 `init()` 初始化。
- **Optimistic UI（乐观更新）**：`useOptimistic.ts` — 操作时前端状态先切换，后端静默同步，失败回滚。配合 `useLocalFirst` 使用。
- **后端调用约定**：一律走 `useApi()` 的 `$fetch` 实例；流式 SSE 端点（`/api/agent/chat`、`/api/agent/expand`）例外，直接用 `fetch + ReadableStream` 绕过 `$fetch`。
- **AI 聊天（/chat 页）**：SSE 帧为结构化 JSON——`{"type":"token","text"}` / `{"type":"tool","label"}`（由 `ToolCallNotifier` 注入）/ `{"type":"confirm","confirmId","action","detail"}`（危险操作确认请求，见下）/ `{"type":"done"}`。短期记忆经 `JpaChatMemoryRepository` 落 `chat_message` 表（滑动窗口 100 条，仅存 USER/ASSISTANT），会话元数据在 `chat_conversation`（id 由前端 UUID 生成）；长期记忆在 `chat_long_memory` 表，Agent 通过 `MemoryTool` 主动保存，每次请求注入 system prompt（≤50 条）。历史/记忆 REST 在 axis-service 的 `ChatHistoryController`（`/api/agent/conversations*`、`/api/agent/memories*`）。
- **危险操作人工确认（confirmation gate）**：删除类 tool（`deleteIssue`/`deleteInboxItem`/`deleteMemory`）不直接执行——经 `ConfirmationService` 发 confirm SSE 帧并挂起 tool 线程，前端内嵌卡片回调 `POST /api/agent/confirm/{confirmId}`（body `{"approved":bool}`，未知/过期 id 404）才放行。5 分钟超时、无活跃流（`/chat/sync`）、流中断一律按拒绝处理。新增危险 tool 时同样接 `ConfirmationService.awaitApproval`。
- **Daily Digest**：跨模块功能。逻辑在 axis-service 的 `com.esmile.axis.digest`（RSS 抓取 → 关键词分类 → 写 `inbox_item`，`type=DIGEST`、`readAt=null`，重跑按 `digest_date` 删旧条目，幂等）；REST 入口在 axis-agent 的 `/api/v1/digest/trigger`。Scheduler 按 cron 每天 10/12/14/20/22 点触发。

## 数据模型

核心实体关系：`Project` 1:N `Issue` 1:N `Comment`；`KnowledgeItem` 1:N `KnowledgeArtifact`；`InboxItem` 独立。Issue 状态枚举：`BACKLOG` / `TODO` / `IN_PROGRESS` / `DONE` / `CANCELLED`。KnowledgeItem type 六枚举：`ARTICLE` / `BOOK` / `PODCAST` / `VIDEO` / `TUTORIAL` / `NOTE`；status 四枚举：`UNREAD` / `READING` / `DONE` / `ARCHIVED`。

## 环境变量

模板见根目录 `.env.example`（前后端共用，复制为根目录 `.env` 即可；`.env` 已 gitignore）。

- `DATABASE_URL`（JDBC 格式，默认 `jdbc:postgresql://localhost:5432/axis`）/ `DB_USERNAME` / `DB_PASSWORD`
- `AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL`：OpenAI 兼容接口。环境变量是兜底——推荐启动后在 Settings 页添加并激活 AI 配置档案，运行时以 DB 档案为准
- `AI_EMBEDDING_MODEL`（默认 `text-embedding-3-small`）：知识库向量检索（pgvector）embedding 模型的 env 兜底。档案按用途分类型（`CHAT`/`EMBEDDING`，Settings 分区管理，每类各激活一个）：embedding 优先取激活的 EMBEDDING 档案（key/endpoint/model 全套），无档案时才用 `AI_API_KEY`/`AI_BASE_URL` + 本变量。请求固定 `dimensions=1536`（对齐 `vector_store` 表；智谱 embedding-3 等可变维度模型也兼容）。切换档案即时生效（reload 事件触发 store 热重建）。分块算法或 embedding 配置变更后，用 `POST /api/knowledge/reindex` 全量重建向量（幂等，逐条先删后写）
- `AXIS_ENCRYPTION_PASSWORD` / `AXIS_ENCRYPTION_SALT`：DB 中 AI API key 加解密用，丢失则已加密 key 不可恢复，生产环境必须更换
- `CORS_ORIGINS`（默认 `http://localhost:7788,http://localhost:3000`）/ `UPLOAD_DIR`（默认 `./uploads`）
- `AXIS_API_BASE`：前端调用的后端地址（默认 `http://localhost:7789`）

**Agent 只读数据库访问**：查数/排障一律用 `axis_readonly` 角色（SELECT-only，已授权 `axis` 库全表含未来新表），不用 `alan`/`postgres` 超级用户做只读查询：

```bash
psql "postgresql://axis_readonly:axis_readonly@localhost:5432/axis"
```

（本地 trust 认证不校验密码，这里的密码仅为文档化；建表/改表等 DDL 才用管理角色。）

## 开发流程

以 `.agents/skills/` 下的技能流为主（原 Spec-Driven 宪章已归档至 `docs/archive/workflow.md`，仅供历史参考）：

- 新需求先 `grill-me` / `grill-with-docs` 对话澄清，把设计各分支问透再动手
- 规格与任务：`to-spec` 产出 spec、`to-tickets` 拆 tracer-bullet tickets，统一落 `.scratch/<feature>/`（约定见 `docs/agents/issue-tracker.md`）
- 实现走 `implement`：预设 seam 处 `tdd`（red-green-refactor），提交前 `code-review`；bug 排查用 `diagnosing-bugs`；跨会话大块工作用 `wayfinder`
- 文档与代码同库同提交
- 直接改、不走流程：缺陷修复、纯样式/文案调整、不改变行为的重构、依赖小版本升级、探索性 spike（在 `docs/spike/` 留一页结论）

## 参数校验（分层，禁止混用）

总原则：**快速失败（fail-fast）**——错误在离源头最近的地方暴露，不带病下传；每层只守自己的边界，不替下游把关。

- **Controller/DTO**：只用 Bean Validation 注解（`@NotNull`/`@Valid`/`@Validated`），不手写 if-throw
- **Controller 方法体**：只做装配 + 一行委托；DTO↔Command/View 转换走 `toXxx`/Assembler
- **业务规则校验**（跨字段/依赖 DB）：放 Service 层；实体状态校验放实体方法（`requireXxx`）
- **公共工具方法**：一行 `Objects.requireNonNull(x, "x")`
- **内部/私有方法**：不做运行时校验，信任边界已把关；不变量可用 `assert` 声明（测试 `-ea` 下生效，生产零成本）；禁止层层重复校验

## 代码风格

- 匹配现有文件的风格、命名与注释密度，不引入自己的默认偏好
- 最小改动解决问题：不加未要求的特性/抽象/可配置性；只改必须改的，不顺手"改进"相邻代码；变更产生的孤立 import/变量要删除，但不删预先存在的死代码
- 后端使用 Lombok；Java 21；包根为 `com.esmile.axis`
- DTO/值对象一律 `record`；除 JPA entity 外禁止 Lombok `@Data`（`@Slf4j` 可用）
- JPA entity 固定注解组合（写在一行）：`@Data @NoArgsConstructor @AllArgsConstructor @Builder`；懒加载字段（`@ManyToOne`/`@OneToMany`/`@ElementCollection` 等）必须加 `@ToString.Exclude @EqualsAndHashCode.Exclude`——否则 `toString` 触发懒加载/循环引用，`equals/hashCode` 基于可变字段会破坏 Set/Map 语义
- 类型分支用 pattern matching switch + sealed；禁止 `instanceof` 后强转
- `Optional` 只作返回值，不作字段/参数
- 集合过滤/转换/聚合默认 Stream 链式
- 构造器注入，禁止字段注入 `@Autowired`
- 事件监听器统一收口到领域 `listener/` 包（如 `knowledge/listener/`），便于快速定位；事件 record 留在各自领域包
- 金额禁止 `float`/`double`；DTO 边界用 `BigDecimal` + Bean Validation，进入领域立即转 Money
- 日志用占位符 `log.info("x={}", x)`，禁止字符串拼接和 `System.out`
- 前端组件目录只扫描 `.vue`（见 `nuxt.config.ts`），避免 `index.ts` 命名冲突
- 文档语言以中文为主；代码标识符用英文

## 编码行为准则（Karpathy's Approach）

行为准则，减少常见 LLM 编码错误。与项目指令合并使用。

**权衡：** 这些准则偏向谨慎而非速度。简单任务时自行判断。

### 1. Think Before Coding

**不假设。不隐藏困惑。呈现权衡。**

实现前：
- 明确陈述假设。不确定就问。
- 存在多种解读时，全部呈现——不要悄悄选择。
- 有更简单方案就说出来。必要时反驳。
- 模糊就停。指出困惑点。问清楚。

### 2. Simplicity First

**最小代码解决问题。不做推测性设计。**

- 不加未要求的特性。
- 单次使用的代码不做抽象。
- 不加未要求的"灵活性"或"可配置性"。
- 不处理不可能发生的错误场景。
- 200 行能写成 50 行，就重写。

问自己："资深工程师会说这太复杂吗？" 是的话，简化。

### 3. Surgical Changes

**只改必须改的。只清理自己制造的混乱。**

编辑现有代码时：
- 不"改进"相邻代码、注释或格式。
- 不重构正常工作的部分。
- 匹配现有风格，即使你做法不同。
- 注意到无关死代码，提出来——不要删除。

变更产生孤立代码时：
- 删除 YOUR 变更导致的无用 import/变量/函数。
- 不删除预先存在的死代码，除非被要求。

测试标准：每行变更都应能追溯到用户请求。

### 4. Goal-Driven Execution

**定义成功标准。循环验证直到达成。**

将任务转化为可验证目标：
- "加验证" → "为无效输入写测试，然后让它们通过"
- "修复 bug" → "写复现它的测试，然后让测试通过"
- "重构 X" → "确保前后测试都通过"

多步任务，简述计划：
```
1. [步骤] → 验证: [检查]
2. [步骤] → 验证: [检查]
3. [步骤] → 验证: [检查]
```

强成功标准让你独立循环。弱标准（"让它能跑"）需要反复澄清。

---

**准则生效的标志：** diff 中无不必要的变更，过度复杂导致的重写减少，实现前有澄清问题而非出错后补救。

Source: [Andrej Karpathy Skills](https://github.com/multica-ai/andrej-karpathy-skills)

## 安全注意事项

- 切勿提交真实密钥：`.env` 已 gitignore，只用 `.env.example` 模板
- AI API key 在 DB 中加密存储，密钥来自 `AXIS_ENCRYPTION_PASSWORD` / `AXIS_ENCRYPTION_SALT`，生产必须更换默认值
- Agent 执行不可逆操作（写删文件、外发请求等）前默认需要人工确认节点，除非在 design.md 中显式豁免

## Agent skills

### Issue tracker

Issues 以本地 markdown 形式存放在 `.scratch/<feature>/` 目录下。详见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认五角色标签：`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`。详见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文布局：根目录 `CONTEXT.md` + `docs/adr/`。详见 `docs/agents/domain.md`。
