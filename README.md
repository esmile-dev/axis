# AI Station - 个人工作站

一个专属的 AI 个人工作站，将零散灵感、日常任务与经验沉淀系统化管理的信息枢纽。

## 功能模块

- **Inbox (灵感回收站)** - 极速收集碎片信息，支持全局快捷键 ⌘K 快速录入
- **Todo (统一待办)** - 全局轻量级行动清单，勾选打卡
- **Kanban (流转看板)** - 产品管理与任务管理，支持拖拽流转和 AI 扩写
- **Knowledge Base (知识树)** - 技术复盘、方法论沉淀

## 技术栈

### 前端框架
- **Nuxt 4** - 全栈 Vue 框架
- **Vue 3** - Composition API
- **TypeScript** - 类型安全

### UI 组件
- **Tailwind CSS** - 原子化 CSS
- **Shadcn-Vue** - 基于 Radix Vue 的组件库
- **Lucide Vue Next** - 图标库

### 状态管理
- **Pinia** - Vue 官方状态管理
- **Optimistic UI** - 乐观更新模式
- **Local-First** - 本地缓存优先渲染

### 后端架构
- **Nuxt Nitro** - 服务端 API Routes
- **Prisma 7** - ORM 数据库工具
- **PostgreSQL** - 数据库（支持 Supabase/Neon）

## 项目结构

```
axis/
├── app/                          # 前端应用
│   ├── components/               # Vue 组件
│   │   ├── ui/                   # Shadcn-Vue UI 组件
│   │   └── CommandPalette.vue    # 全局命令面板
│   ├── composables/              # 组合式函数
│   │   ├── useLocalFirst.ts      # 本地优先缓存
│   │   └── useOptimistic.ts      # 乐观更新
│   ├── generated/                # Prisma 生成的客户端
│   ├── layouts/                  # 布局组件
│   ├── pages/                    # 页面路由
│   │   ├── index.vue             # Inbox 页面
│   │   ├── todo.vue              # Todo 页面
│   │   ├── kanban.vue            # Kanban 页面
│   │   ├── knowledge.vue         # Knowledge 页面
│   │   └── settings.vue          # 设置页面
│   └── app.vue                   # 根组件
├── server/                       # 服务端
│   ├── api/                      # RESTful API
│   │   ├── inbox/                # Inbox CRUD
│   │   ├── todo/                 # Todo CRUD
│   │   ├── kanban/               # Kanban CRUD
│   │   ├── knowledge/            # Knowledge CRUD
│   │   └── ai/                   # AI 扩写 API
│   └── utils/
│       └── prisma.ts             # Prisma 客户端
├── prisma/
│   └── schema.prisma             # 数据库模型
├── assets/
│   └── css/
│       └── tailwind.css          # 全局样式
├── docs/                         # 项目文档
├── .env                          # 环境变量
├── prisma.config.ts              # Prisma 7 配置
├── nuxt.config.ts                # Nuxt 配置
└── tailwind.config.js            # Tailwind 配置
```

## 快速开始

### 1. 安装依赖

```bash
npm install
```

### 2. 配置数据库

创建 `.env` 文件：

```env
DATABASE_URL="postgresql://user:password@host:5432/database?schema=public"
```

推荐使用：
- [Supabase](https://supabase.com) - 免费额度充足
- [Neon](https://neon.tech) - Serverless PostgreSQL

### 3. 生成 Prisma 客户端

```bash
npx prisma generate
```

### 4. 运行数据库迁移

```bash
npx prisma migrate dev --name init
```

### 5. 启动开发服务器

```bash
npm run dev
```

访问 http://localhost:3000

## 核心特性

### Local-First 首屏秒开

页面优先从 LocalStorage 读取缓存立即渲染，后台静默同步数据库，确保每次冷启动都是毫秒级响应。

### Optimistic UI 乐观更新

所有操作前端状态先切，后端静默同步，无需等待 Loading，保持心流体验。

### 全局快捷键

- `⌘K` / `Ctrl+K` - 唤起命令面板，快速录入或跳转

### AI 扩写

在 Kanban 任务卡片点击 AI EXPAND 按钮，自动生成 PRD 格式内容。支持配置自定义 API Key。

## 生产部署

### 构建

```bash
npm run build
```

### 预览

```bash
npm run preview
```

### 环境变量

生产环境需要配置：

```env
DATABASE_URL="your-production-database-url"
NODE_ENV="production"
```

## 数据模型

```prisma
model InboxItem {
  id        String   @id @default(cuid())
  content   String
  createdAt DateTime @default(now())
  updatedAt DateTime @updatedAt
}

model TodoItem {
  id        String   @id @default(cuid())
  title     String
  completed Boolean  @default(false)
  createdAt DateTime @default(now())
  updatedAt DateTime @updatedAt
}

model KanbanTask {
  id          String   @id @default(cuid())
  title       String
  description String?
  status      String   @default("Todo")
  order       Int      @default(0)
  createdAt   DateTime @default(now())
  updatedAt   DateTime @updatedAt
}

model KnowledgeDocument {
  id        String   @id @default(cuid())
  title     String
  content   String
  createdAt DateTime @default(now())
  updatedAt DateTime @updatedAt
}
```

## License

MIT
