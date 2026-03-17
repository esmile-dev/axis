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