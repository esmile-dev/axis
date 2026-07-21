# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AI Station - 个人工作站，管理从灵感捕捉到需求落地的完整生命周期。核心模块：Inbox（灵感回收站）、Projects/Issues（任务流转）、Knowledge Base（知识沉淀）。

## Development Commands

```bash
cd frontend && npm run dev          # 启动前端 (localhost:7788)
cd frontend && npm run build        # 生产构建
cd frontend && npm run preview      # 预览构建结果
cd frontend && npm run test         # Vitest 跑全部前端测试
cd frontend && npx vitest run <file>  # 跑单个测试文件

cd backend && mvn spring-boot:run -pl axis-agent -am   # 启动 Spring Boot 后端 (localhost:7789)
cd backend && mvn compile           # 编译后端
cd backend && mvn test -pl axis-service -Dtest=SomeTest  # 跑单个后端测试
```

注意：后端只有 `axis-agent` 模块含 `AxisApplication` 主类，`spring-boot:run` 必须指定 `-pl axis-agent`（`-am` 同时构建依赖的 `axis-service`）。仓库无 Maven Wrapper，使用系统 `mvn`。

## Tech Stack

- **前端**：Nuxt 4 + Vue 3 Composition API + TypeScript
- **后端**：Spring Boot 4.0 + Spring Data JPA + Spring AI 2.0（见 `backend/`）
- **数据库**：PostgreSQL（本地默认 `postgres` 库，schema 为 `public`，连接配置见 `application.yml`）
- **UI**：Tailwind CSS + Shadcn-Vue (基于 Reka UI) + Lucide Vue Next 图标
- **状态管理**：Pinia + VueUse

## Architecture

前后端分离：前端通过 `frontend/app/composables/useApi.ts`（基于 `runtimeConfig.public.apiBase`）调用 Spring Boot REST API。

### Local-First (首屏秒开)
`frontend/app/composables/useLocalFirst.ts` - 页面优先从 LocalStorage 读取缓存立即渲染，后台静默同步数据库。所有使用此模式的页面必须调用 `init()` 初始化。

### Optimistic UI (乐观更新)
`frontend/app/composables/useOptimistic.ts` - 操作时前端状态先切换，后端静默同步，失败则回滚。配合 `useLocalFirst` 使用。

### 后端调用约定
- 全部走 `useApi()` 的 `$fetch` 实例，自动带 `baseURL` 指向 Spring Boot
- 流式 SSE 端点（`/api/agent/chat`、`/api/agent/expand`）直接用 `fetch + ReadableStream`，绕过 `$fetch`

### Daily Digest（每日技术摘要）
跨模块功能：逻辑在 `axis-service` 的 `com.axis.digest`（RSS 抓取 → 关键词分类 → 写入 `daily-YYYY-MM-DD.md` 到 `DIGEST_INBOX_DIR`，默认 `./inbox`），REST 入口在 `axis-agent` 的 `/api/v1/digest`（trigger/latest/recent）。Scheduler 按 cron 每天 10/12/14/20/22 点触发。需求文档见 `docs/feature/daily-digest/requirements.md`。

## Key Structure

```
frontend/
├── app/
│   ├── components/
│   │   ├── ui/              # Shadcn-Vue 基础组件
│   │   ├── CommandPalette.vue  # ⌘K 全局命令面板
│   │   └── Issue*.vue       # Issue 相关业务组件
│   ├── composables/         # useApi, useLocalFirst, useOptimistic, useImageUpload
│   ├── pages/
│   │   ├── index.vue        # Inbox 页面
│   │   ├── todo.vue         # Todo 页面
│   │   ├── projects/        # Project 管理
│   │   ├── issues/[id].vue  # Issue 详情
│   │   └── knowledge.vue    # 知识库
├── nuxt.config.ts
├── package.json
└── tailwind.config.js
backend/
├── pom.xml                  # 父 POM (packaging=pom)，无 Maven Wrapper
├── inbox/                   # Daily Digest 输出目录 (daily-YYYY-MM-DD.md)
├── axis-service/            # 业务核心模块 (jar，无主类不可独立启动)
│   └── src/main/java/com/axis/
│       ├── controller/      # REST API（Inbox/Project/Issue/Knowledge/FileUpload/Health）
│       ├── service/         # 业务逻辑
│       ├── repository/      # Spring Data JPA
│       ├── entity/          # JPA 实体
│       ├── enums/           # 枚举定义
│       ├── digest/          # Daily Digest：fetch/classify/scheduler/store
│       └── config/          # CORS 配置
└── axis-agent/              # AI Agent 模块（依赖 axis-service，产出可执行 fat jar）
    └── src/main/java/com/axis/
        ├── AxisApplication.java  # Spring Boot 主类（扫描整个 com.axis）
        ├── config/AiConfig.java  # ChatClient / ChatMemory 配置
        ├── digest/controller/    # /api/v1/digest REST 入口
        └── ai/              # Spring AI Agent（controller/tool，Tools: Inbox/Issue/Project/Knowledge）
docs/
├── workflow.md          # 开发流程宪章：分级/门禁/豁免（开工前必读）
└── feature/             # 功能文档：_template/ + <功能名>/{requirements,design,tasks,summary}.md
```

后端配置集中在 `axis-service/src/main/resources/application.yml`（端口、数据源、AI、digest cron/RSS 源均支持环境变量覆盖）。

## Data Model

核心实体关系：`Project` 1:N `Issue` 1:N `Comment`。Issue 使用枚举定义 `IssueStatus`/`IssuePriority`/`IssueType`。

## Feature 开发文档流 (Spec-Driven)

详细流程见 `docs/workflow.md`（**开工前必读**），核心规则：

- 新需求先分级（S/M/L），AI 提议、用户确认：S 直接改；M 写一份 `lite-spec.md`；L 走完整文档流
- L 级在 `docs/feature/<功能名>/` 依次产出 requirements → design → tasks；每份 front-matter 的 `status` 被确认改为 `approved` 后才进入下一阶段；验收通过（verified）后写 summary 复盘归档
- 编码严格按 tasks 任务表执行：完成一个任务打一个勾并填 commit hash；不做任务表之外的事
- 需要偏离已批准文档时：停下，在该文档「变更记录」写明，等确认后再继续
- 文档风格：语言简洁，优先 Mermaid 图和表格；模板在 `docs/feature/_template/`

## Coding Guidelines (from Karpathy's Approach)

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

## Pre-Commit

**提交前必须测试。** 未验证 = 未完成。

## Design & Interaction Reference

参考 **Linear.app** 的设计与交互风格：

- **极简高效**：界面干净，信息密度高，无冗余装饰
- **键盘优先**：快捷键操作流畅，⌘K 命令面板可触达所有功能
- **暗色主题**：默认深色背景，配色克制，强调对比度而非色彩
- **流畅动画**：状态切换有微妙过渡，拖拽排序自然顺滑
- **即时反馈**：操作立即响应，加载状态用骨架屏而非 spinner