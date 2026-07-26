# AI Station - 个人工作站

一个专属的 AI 个人工作站，将零散灵感、日常任务与经验沉淀系统化管理的信息枢纽。

## 功能模块

- **Inbox (灵感回收站)** - 极速收集碎片信息，支持全局快捷键 ⌘K 快速录入
- **Todo (统一待办)** - 全局轻量级行动清单，勾选打卡
- **Projects / Issues** - 项目与任务管理，支持拖拽流转和 AI 扩写 PRD
- **Knowledge Base (知识树)** - 技术复盘、方法论沉淀
- **AI Agent** - 用自然语言操作系统（Spring AI + Tool Calling）

## 技术栈

### 前端
- **Nuxt 4** + **Vue 3** Composition API + **TypeScript**
- **Tailwind CSS** + **Shadcn-Vue** (基于 Reka UI) + **Lucide Vue Next** 图标
- **Pinia** + **Local-First** + **Optimistic UI**

### 后端
- **Spring Boot 4.0** + **Spring Framework 7.0** + **Java 17**
- **Spring Data JPA** + Hibernate
- **Spring AI 2.0** (Agent + ChatClient + Tool Calling)
- **PostgreSQL**（共用 axis schema）
- **Maven** 构建

## 项目结构

```
axis/
├── frontend/                     # 前端（Nuxt）
│   ├── app/                      # Nuxt 应用源码
│   │   ├── components/           # Vue 组件
│   │   ├── composables/          # useApi / useLocalFirst / useOptimistic
│   │   ├── layouts/              # 布局
│   │   └── pages/                # 路由页面
│   ├── nuxt.config.ts
│   ├── package.json
│   └── tailwind.config.js
├── backend/                      # 后端（Spring Boot）
│   ├── src/main/java/com/axis/
│   │   ├── controller/           # REST API
│   │   ├── service/              # 业务层
│   │   ├── repository/           # Spring Data JPA
│   │   ├── entity/               # JPA 实体
│   │   ├── enums/                # 枚举
│   │   ├── config/               # CORS / AI 配置
│   │   └── ai/                   # Spring AI Agent（Tools / Controller）
│   └── pom.xml
└── docs/                         # 项目文档
```

## 快速开始

### 1. 启动 PostgreSQL

确保本地有 PostgreSQL，且 `axis` 数据库已创建（schema 名为 `axis`）。

### 2. 启动后端（端口 8080）

```bash
cd backend
./mvnw spring-boot:run
```

环境变量（可通过 `.env` 或 IDE 配置）：
- `DATABASE_URL`（默认 `jdbc:postgresql://localhost:5432/axis?currentSchema=axis`）
- `DB_USERNAME` / `DB_PASSWORD`
- `AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL`

完整变量模板见根目录 `.env.example`（前后端共用，复制为根目录 `.env` 即可）。

### 3. 启动前端（端口 7788）

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:7788

可选环境变量：`AXIS_API_BASE`（指向后端地址，默认 `http://localhost:8080`）

## 核心特性

### Local-First 首屏秒开

页面优先从 LocalStorage 读取缓存立即渲染，后台静默同步数据库。

### Optimistic UI 乐观更新

所有操作前端状态先切，后端静默同步，无需等待 Loading。

### AI Agent

通过 `/api/agent/chat` 与 LLM 对话，Agent 自动调用 Tools（Inbox / Issue / Project / Knowledge）操作数据。

### 全局快捷键

- `⌘K` / `Ctrl+K` - 唤起命令面板

## 数据模型

```
Project ─1:N─→ Issue ─1:N─→ Comment
InboxItem (独立)
KnowledgeDocument (独立)
```

Issue 状态：`TODO` / `IN_PROGRESS` / `IN_REVIEW` / `DONE` / `CANCELLED`

## 生产部署

```bash
cd frontend && npm run build
cd frontend && npm run preview
```

后端打包：`cd backend && ./mvnw package`

## License

MIT