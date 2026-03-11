# AI 个人工作站 - PRD

## 1. 概览 (Overview)

### 1.1 产品愿景

打造一个专属的“AI 个人工作站”。它不仅仅是一个笔记软件或 To-Do 列表，而是一个**将零散灵感、日常任务与经验沉淀系统化管理的信息枢纽**。核心目的是解决时间碎片化带来的上下文丢失，并利用大模型能力自动化信息的整理与延展。

### 1.2 Target Audience (目标受众)

- 独立开发者、产品经理、技术写作者。
- 需要高频切换上下文的脑力工作者。

### 1.3 核心功能模块定位

1. **全域收件箱 (Inbox)**：每日信息的极速收集（网页摘抄、一闪念的 Idea、待办杂项）。
2. **项目与看板 (Projects & Kanban)**：项目级别的组织管理。左侧导航栏 `Projects` 列表下展示每一个项目，点击具体项目即进入该项目的**流转看板 (Kanban)** 页面。在此不仅管理跨项目流转状态（Planning → Active等），也在专属看板上将 Idea 扩写为需求、通过拖拽管理 Issue 流转和产出追踪。
3. **知识沉淀集 (Knowledge Base)**：技术复盘、方法论调整、沉淀经验（支持双向链接或 Markdown 归档）。
4. **统一待办 (Todo)**：全局轻量级行动清单，**也是系统中不归属任何 Project 的独立 Issue 的聚合视图**。支持通过标准 Issue 面板创建短平快的任务，同时作为（未来）汇总其他模块中次生任务的集散地。

---

## 2. 核心模块与 JTBD (Jobs-To-Be-Done) 分析

### 2.1 模块一：全域收件箱 (Inbox)

**目标**：无阻力、极速记录碎片信息（类似 Linear 极致的 Keyboard-First 体验）。

- **JTBD 1 (快捷记录)**：当我在工作时脑海中突然闪过一个灵感或任务时，我想要按下一个全局快捷键（如 `Cmd+K` 或单键 `C`）唤出一个极简悬浮输入框，随意打出文字回车保存，以便于我能在一秒内清空大脑并回到当前工作流程。输入框应当像 Linear UI 一样瞬间响应，无明显刷新。
- **JTBD 2 (网页剪藏)**：当我在浏览有价值的技术文档或网页时，我想要高亮网页内容并一键发送到我的 Inbox 中，以便于我之后能对它进行深度阅读或归档到知识库。
- **JTBD 3 (AI 自动分类 - V2规划)**：当我积攒了大量乱七八糟的 Inbox 草稿时，我想要一键调用 AI 帮我自动打标签（如：#灵感、#待回邮件、#今日待办），以便于我能快速过滤。

### 2.2 模块二：项目与流转看板 (Projects & Kanban)

**目标**：将工作按项目维度组织，通过左侧导航直达单项目内的专属看板视图，将 Idea 转化为可执行任务并直观管理。

- **JTBD 4 (项目创建与导航)**：当我有一个新的工作计划时，我想要快速创建一个项目。同时我想要在左侧导航栏 `Projects` 菜单下直接看到所有项目的列表，点选任意项目即可直接进入它的详情看板。
- **JTBD 5 (项目状态流转)**：当项目从规划阶段进入执行阶段时，我想要一键更新项目状态（Planning → Active → Completed → Archived），以便于追踪项目的整体进度。
- **JTBD 6 (Issue 管理与看板交互)**：当我点击进入某个具体项目时，我想要看到该项目专属的**流转看板**页面。在这里我可以创建 Issue，设置完整的属性（Priority, Status, Type, Attachment 等），并通过界面上的级联瀑布流（Todo → In Progress → Done）拖拽方式来管理 Issue 的状态，直观追踪最新进展。**Issue 的创建必须参考 Linear 的模式——通过右侧滑出的悬浮面板 (Slide-over) 进行，标题和描述区在上方，底部是一排极简的属性设置栏 (Status / Priority / Type / Project / Attachment)。**
- **JTBD 7 (Idea 转 PRD)**：当我要开始一个新功能开发时，我可直接在该项目的看板上新建卡片，并**点击卡片上极简的"AI 扩写"图标(✨)**，让 AI 按照系统模板自动生成初始需求，即刻开启 Vibe Coding。**（需支持自定义 API Key/Model，必须具备真实的流式 Streaming 输出效果）。**
- **JTBD 8 (需求流转操作)**：当我需要扭转需求状态时，除了拖拽外，我还想直接使用键盘快捷键（如按数字 `1,2,3` 切换状态视图），且 UI 必须运用乐观更新（Optimistic UI）瞬间响应，保持心流 (Momentum)。
- **JTBD 9 (AI 评审 - V2规划)**：当我的 PRD 准备进入 Implement 时，我可以通过侧边栏让 AI 对卡片内容进行逻辑漏洞审查，前置发现边界条件遗漏。

### 2.3 模块三：知识沉淀集 (Knowledge Base)

**目标**：持久化经验，提升后续复用率。

- **JTBD 10 (独立复盘记录)**：当我在开发中踩了一个坑或完成了一个重要功能时，我想要直接在知识库中新建一篇"复盘总结"，将其独立归档沉淀，以便于日后遇到相似功能时可以直接检索参考。
- **JTBD 11 (文档关联与双联 Auto-Linking)**：当我在记录修复踩坑记录时，我想要能够手动输入 `@` 符号快速链接历史文档。更关键的是，**AI 应在后台自动对我的 Inbox 和任务内容进行语义检索，自动在侧边栏显示 `隐形关联 (Related Docs: N)`，自发将散落的知识自动连接成网。**

### 2.4 模块四：统一待办 (Todo)

**目标**：提供一个无压力的全局行动打卡清单。

- **JTBD 12 (统一 Issue 待办)**：当我想起一个不需要写长篇大论的琐碎任务（如"查阅这篇论文"或"回复邮件"）时，我想要在一个全局的 Todo 列表里快速唤起标准 Issue 创建面板加一条任务，并不挂载到具体项目。只要勾选完成即可。
- **JTBD 13 (衍生待办汇总 - V2规划)**：当专属看板中的卡片正在推进，或者我在编写知识库时产生了一个微小的跟进点系统将其转化为无归属的 Issue，并在**主 Todo 列表中自动聚合**，以便于每天统筹查看打卡。

---

## 3. 技术基准与架构设计规范 (Tech & Architecture Baseline)

为了适应 Vibe Coding 流程，强制 AI 在后续生成代码时遵循以下基准：

### 3.1 核心技术栈 (Vue 全栈体系)

- **前端框架**：Nuxt 3 (全栈框架), Vue 3 (Composition API), TypeScript
- **UI 组件库**：Tailwind CSS, Shadcn-Vue (基于 Radix Vue，实现现代极简的黑白灰色调体系，带微末阴影厚度与细微毛玻璃效果，复刻 Linear 视觉美学)
- **状态管理**：Pinia (Vue 官方轻量级状态管理，必须运用 Optimistic UI 乐观更新模式，确保极致的响应速度)
- **快捷键库**：引入类似 `vueuse/useMagicKeys` 或 `cmdk-vue` 实现纯键盘流操作。
- **后端架构**：Nuxt 3 Nitro API Routes **（所有 API 服务必须严格遵循 RESTful HTTP 规范，使用标准动词 GET/POST/PUT/PATCH/DELETE，保持清晰的资源路径结构）**。
- **数据库 ORM**：Prisma
- **数据库引擎**：PostgreSQL, 结合 Serverless 数据库平台（如 Supabase 或 Neon）。这能完美支持在任意一台笔记本上拉取云端数据，实现多端无缝同步，同时保证毫秒级查询速度。

### 3.2 界面视觉与核心交互规范 (Linear Design Philosophy)

为了达到极致的高级感与效率，全局界面生成必须严格遵循以下 **Linear Style** 设计流派的四大理念：

- 必须默认使用暗色深黑或超深灰背景。
- 字体采用现代无衬线体（如 Inter），通过字重和深浅度（亮度）而非大小来表现排版层级。
- 在卡片悬停边缘、主要 Logo 或核心按钮处，可运用微量且克制的紫/蓝/灰“线性渐变光晕 (Linear Gradients)”。

1. **去边框化与深度感知 (Borderless & Depth)**：
   - 严禁使用粗暴、高对比度的实线边框来切割区域。
   - 必须通过极细微的背景色差 (Surface Colors) 和外部微投影 (Subtle Drop Shadows) 来区分卡片、侧边栏和主区域，构建页面的立体空间感。

2. **微动效与非阻断式体验 (Micro-interactions & Non-blocking)**：
   - 所有状态切换（如 Hover、勾选）需伴有极其顺滑短暂的过渡动效（透明度或缩放）。
   - **绝对禁止滥用阻断式的居中弹窗 (Modals Popups)**。新建任务、编辑详情必须通过“侧滑面板 (Slide-over panel)”或“卡片内联展开 (Inline editing)”完成。

3. **高信噪比与瞬间响应 (High Signal-to-Noise Ratio & Optimistic UI)**：
   - 界面上平时只展示核心信息，将不常用的操作按钮隐藏，仅在鼠标悬停在对应卡片时才浮现（如状态扭转、打标签按钮）。
   - 所有状态提交操作不需要 Loading 菊花图，利用 Pinia 实现“前端状态先切，后端静默同步”的乐观更新机制。
   - **核心基准：Local-First 数据同步首屏秒开机制**。解决云端同步时的白屏风险：首页渲染必须优先无条件从本地（如 LocalStorage）极速获取缓存卡片渲染，随后在后台静默向 Supabase 获取增量拉取修正，确保每一次冷启动也都是“心流级”的毫秒瞬间。

### 3.3 不做的事情 (Negative Requirements / Non-Goals)

*这些是生成代码时严禁实现的内容，以防止功能膨胀。*

1. **不做多用户系统**：当前版本纯为单点个人使用，所有的同步和访问依然基于唯一的“个人工作站”概念，不设计复杂的鉴权、角色控制和团队分享体系。
2. **不做复杂的富文本编辑器**：知识库和 Inbox 内容目前**仅支持原生 Markdown 纯文本渲染**，不要引入 Quill, Draft.js 等重量级富文本编辑器。

---

## 4. MVP (最小可行性产品) 阶段交付清单 (DoD)

第一阶段我们需要聚焦于让这个工作站能“核心运转”起来，以下是判断 MVP 是否完成的验收标准：

### 阶段一：领域模型建立 (Schema)

- [ ] 依据文档中的 4 个核心模块，成功输出 Prisma `schema.prisma` 文件。
- [ ] 包含实体：`InboxItem`, `Project`, `Issue`, 以及 `KnowledgeDocument`。（注：TodoItem 已统一为 Issue，通过缺少 projectId 来区分）。
- [ ] `Project` 模型包含：name, description, status (PLANNING/ACTIVE/COMPLETED/ARCHIVED), order
- [ ] `Issue` 模型包含：title, description, status (BACKLOG/TODO/IN_PROGRESS/DONE/CANCELLED), priority (NONE/LOW/MEDIUM/HIGH/URGENT), type (BUG/FEATURE/IMPROVEMENT), order, attachment, projectId (可选)

### 阶段二：UI 框架搭建

- [ ] 生成一个采用侧边导航栏 (Sidebar) 并且整体风格极简的主页面。
- [ ] 左侧导航栏包含：核心的 `Inbox (灵感回收站)`, `Todo (统一待办)`, `Knowledge Base (知识树)`，以及一个可展开的 `Projects` 列表块（下方直接列出各个创建好的 Project）。

### 阶段三：核心交互（连通全栈）

- [ ] **Inbox 列表页**：顶部有一个大的输入框，按 Enter 即可快速把灵感推入下方的列表。列表中支持直接删除。
- [ ] **Todo 待办列表**：简单勾选打卡、分类或更改紧急程度的极快清单。**统一使用 Issue 的横向列表展示，包含优先级和标签等标记**。
- [ ] **Projects 项目总览页**（点击 Projects 标题栏）：展示所有项目概览，支持创建、删除项目，更改项目总体状态。
- [ ] **专属看板交互页**（点击左侧具体项目）：进入该专属项目的 Issue 看板页面，实现经典多列瀑布流（Backlog/Todo/In Progress/Done）。可直接新建 Issue 并支持拖拽流转、编辑/删除。 **新建 Issue 采用向右弹窗设计的滑动面板操作，包含完整的 Title/Description 以及底部状态栏 (Status/Priority/Type/Project/Attachment)。**
- [ ] **AI 侧边栏/浮层**：在单项目中任一看板卡片内，引入简单按钮：调用 API 模拟"自动将该任务描述扩写为 PRD 格式"的过程（保证交互流畅，假数据占位亦可）。

---
**本 PRD 为 Vibe Coding 规范提示词格式。在接下来的代码生成环节，所有 Agent 行为都不脱离这份文档的范围。**
