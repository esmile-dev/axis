# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AI Station - 个人工作站，管理从灵感捕捉到需求落地的完整生命周期。核心模块：Inbox（灵感回收站）、Projects/Issues（任务流转）、Knowledge Base（知识沉淀）。

## Development Commands

```bash
npm run dev          # 启动开发服务器 (localhost:3000)
npm run build        # 生产构建
npm run preview      # 预览构建结果

npx prisma generate  # 生成 Prisma 客户端（修改 schema 后必须执行）
npx prisma migrate dev --name <name>  # 创建迁移
npx prisma studio    # 打开数据库 GUI
```

## Tech Stack

- **Nuxt 4** + **Vue 3** Composition API + **TypeScript**
- **Prisma 7** ORM + **PostgreSQL**（支持 Supabase/Neon）
- **Tailwind CSS** + **Shadcn-Vue** (基于 Reka UI) + **Lucide Vue Next** 图标
- **Pinia** 状态管理 + **VueUse** 工具库

## Architecture Patterns

### Local-First (首屏秒开)
`app/composables/useLocalFirst.ts` - 页面优先从 LocalStorage 读取缓存立即渲染，后台静默同步数据库。所有使用此模式的页面必须调用 `init()` 初始化。

### Optimistic UI (乐观更新)
`app/composables/useOptimistic.ts` - 操作时前端状态先切换，后端静默同步，失败则回滚。配合 `useLocalFirst` 使用。

### Prisma 客户端位置
Prisma 客户端生成到 `app/generated/prisma/` 而非默认的 `node_modules`，需要在 `app/` 内部导入。

## Key Structure

```
app/
├── components/
│   ├── ui/              # Shadcn-Vue 基础组件
│   ├── CommandPalette.vue  # ⌘K 全局命令面板
│   └── Issue*.vue       # Issue 相关业务组件
├── composables/         # useLocalFirst, useOptimistic
├── generated/prisma/    # Prisma 生成的客户端
├── pages/
│   ├── index.vue        # Inbox 页面
│   ├── todo.vue         # Todo 页面
│   ├── projects/        # Project 管理
│   ├── issues/[id].vue  # Issue 详情
│   └── knowledge.vue    # 知识库
server/
├── api/                 # RESTful API（按资源分目录）
└── utils/prisma.ts      # Prisma 单例，使用 PrismaPg 适配器
prisma/schema.prisma     # 数据模型定义
```

## Data Model

核心实体关系：`Project` 1:N `Issue` 1:N `Comment`。Issue 使用枚举定义 `IssueStatus`/`IssuePriority`/`IssueType`。

## Development Philosophy (from PRD)

方法论四阶段：
1. **诊断期**: 从真实痛点收集需求
2. **收敛期**: DDD 划定边界，确定技术栈
3. **设计期**: JTBD 框架 - `当 [场景] 时，我想要 [行动]，以便于 [效果]`
4. **破冰期**: Vibe Coding - 分步喂上下文，小步快跑验收

详见 `docs/方法论与产品idea.md`

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

Nitro 动态路由：`[param]/index.get.ts` ✓，`[param].get.ts` ✗（被 Vue Router 拦截）