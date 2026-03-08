# AI 个人工作站 - 演进与知识点备忘录 (TODO & Knowledge)

## 📌 当前架构约定 (Current Constraints)

1. **模块相互独立原则 (MVP 阶段)**
   目前系统内的三大模块：`Inbox (收件箱)`、`Kanban (流转看板)`、`Knowledge Base (知识库)` 在数据结构与逻辑上是**相互独立**的。
   - 现阶段**不做**“从 Inbox 拖拽条目自动生成 Kanban 卡片”的转换逻辑。
   - 现阶段**不做**“Kanban 卡片完毕后自动格式转换并转移进入知识库”的流转逻辑。
   - *（注：保持各模块闭环，极大降低第一版的数据复杂度，快速跑通核心逻辑）*

---

## 🚀 未来待办列单 (TODOs / Future Epics)

### 1. 跨模块数据打通 (Data Interoperability)

- [ ] **Inbox -> Kanban**：实现拖拽/一键转换机制，能将一条 Inbox 点子带上上下文直接转化为看板的一张 PRD 初始卡片。
- [ ] **Kanban -> Knowledge**：为看板的 Done 列增加“一键脱离归档”功能，将任务描述连同复盘记录一起转化成知识库的一篇独立 Markdown 文档，防止看板长期堆积爆满。
- [ ] **跨模块 -> 全局 Todo 聚合**：允许用户在 Kanban 卡片详情页或 Knowledge 文档内部，圈出一段话并“生成代办 (Todo)”。这些分散的微型待办将被自动聚合到全域的 Todo 模块中统一展现。

### 2. AI 能力深度探索 (AI Capabilities)

- [ ] **Inbox 自动打标**：对收集箱内的一闪念进行自动分类提取。
- [ ] **知识库 Auto-Linking**：依靠大模型对所有离散的知识点、报错记录通过语义向量搜索（Vector Search）建立自动关联，在文档侧边栏提示 `隐性关联 (Related Docs: N)`。

### 3. 工程化与运维 (Engineering)

- [ ] **Supabase 集成与全覆盖身份校验**：从本地纯 SQLite 升级为全 Serverless 同步（虽然是单用户，但也需要简单的 JWT 凭证确保公网访问不会被盗用）。
- [ ] **离线能力 (Offline Support)**：更完善的 Local-First 方案，断网仍可全功能使用，连网后 CRDT 或简单覆盖同步。
