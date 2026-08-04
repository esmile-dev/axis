---
status: approved       # G1 通过 2026-08-03
feature: knowledge-2.0
created: 2026-08-03
---

# 需求文档：知识库 2.0（Knowledge Base 2.0）

<!-- 决策层：G1 逐字审，≤1 页 -->

## 1. 背景与目标

现有 knowledge 模块只有 `title + content` 一张平表和一个不渲染内容的卡片页，是"表皮"。2.0 从零重建为**内容消化型知识库**：多形态内容（文章/书籍/笔记先行，播客/视频预留）统一入库，AI 自动加工出总结与脑图，同一份原文支持原文/总结/脑图三视图消费，并可针对单条内容问答、被 Agent 真正检索。本期覆盖 P1（摄取+三视图+状态进度+Inbox 转入）+ P2（问答+向量检索）。

## 2. 功能需求（FR）

| 编号 | 需求描述 | 优先级 | 验收标准（一句话，可验证） |
|------|----------|--------|---------------------------|
| FR-001 | 粘贴创建：提交标题+正文（类型 ARTICLE/BOOK/NOTE、标签、来源 URL 可选），入库即触发异步加工 | P0 | POST 后列表可见，条目加工状态为 PENDING/GENERATING |
| FR-002 | URL 抓取：提交 URL 自动抓正文转 Markdown 入库，标题自动提取 | P0 | 给真实文章 URL，条目 content 为正文 Markdown 且无导航/广告残渣；抓取失败返回 422 及原因 |
| FR-003 | 文件导入：上传 .md/.txt/.pdf（≤20MB）解析为 Markdown 入库 | P0 | 三种文件各上传一个，content 正确；>20MB 或不支持类型被拒并提示 |
| FR-004 | 自动派生产物：入库后异步生成「总结」与「脑图大纲」，状态机 PENDING→GENERATING→DONE/FAILED 可见，失败可手动重试 | P0 | 入库后无需操作，两产物最终 DONE 可在详情查看；注入 LLM 故障 → FAILED 且原文无损，点重试恢复 |
| FR-005 | 三栏浏览：左栏类型/状态/标签筛选，中栏条目列表（类型图标、状态、进度、产物状态点），关键词搜索 | P0 | 组合筛选+搜索返回正确子集；列表不加载 content 全文 |
| FR-006 | 详情三视图：原文（Markdown 渲染）/ 总结 / 脑图（markmap 交互，可切文字大纲） | P0 | 三 tab 均可正常展示；脑图可缩放、折叠展开节点 |
| FR-007 | 阅读状态与进度：UNREAD/READING/DONE/ARCHIVED 手动切换；阅读进度按滚动位置自动记录（防抖） | P0 | 切换状态即刻生效并筛选一致；滚动后刷新页面进度保留 |
| FR-008 | Inbox 一键转入：Inbox 条目「转入知识库」，链接类走抓取管线、文字类直接入库，转入后原条目标记已读 | P0 | 两类条目各转一条，知识库出现对应条目且 inbox 条目 readAt 非空 |
| FR-009 | 条目 AI 问答（P2）：详情页内针对该条目 SSE 流式问答，历史按条目持久化 | P0 | 提问答案源自该条目内容（评测集判定）；刷新页面历史仍在 |
| FR-010 | Agent 检索升级（P2）：条目分块 embedding 入 pgvector，`KnowledgeTool` 改语义检索；pgvector 缺失或 embedding 失败自动降级 DB 关键词检索 | P1 | 用语义相近词（非标题原词）能搜到目标条目；禁用 embedding 后检索仍可用且日志有 WARN |

## 3. 非功能需求（NFR）

| 编号 | 需求 | 验收 |
|------|------|------|
| NFR-001 | 单次 LLM 调用 60s 超时，超时按该产物失败处理 | mock 延迟 >60s → 产物 FAILED 且可重试 |
| NFR-002 | 摄取接口不阻塞于 LLM（异步加工）；URL 抓取自身 15s 超时 | 粘贴 POST P95 < 2s；抓取超时返回 422 |
| NFR-003 | AI 功能可评测：评测集与脚本随库 `docs/feature/knowledge-2.0/evals/` | 评测入口可运行，结果见 tasks 验收记录 |
| NFR-004 | 总结评测：≥10 篇真实中英文文章，结构合格率（TL;DR 存在 + 要点 ≥3）100%，LLM-judge 相关性 ≥4/5 占比 ≥80%；脑图评测：大纲合法性（标题层级 ≤4、节点 5–40 个）脚本判定合格率 ≥90% | 评测实测达标 |
| NFR-005 | 问答评测：≥5 条 golden QA，答案须源自原文（judge 判定），通过率 ≥80% | 评测实测达标 |

## 4. 范围外（Non-Goals）

- 播客/视频的转录与播放（类型枚举预留，P3 再做）
- 高亮、批注、划线笔记
- 条目间关联与「相关推荐」（沿用 spike 结论，后续单独立项）
- 超长文 map-reduce 总结（本期截断至模型安全长度并在产物中标注）
- 知识库与 Issue/Project/Inbox 的反向关联
- 浏览器插件、移动端分享入口
- 问答挂业务工具（本期纯 source-grounded 问答，不可操作数据）

## 5. 待确认问题

无。关键决策已全部拍板（AI 提议、用户确认）：摄取=粘贴+URL+文件；产物=入库异步自动生成；组织=类型分区+标签；AI=问答+检索升级；Inbox=一键转入；状态=四态+滚动进度。

---

> **细节层（实现参考，审阅可选）**

### FR-002 抓取细节

- 后端 Jsoup 抓 HTML（15s 超时，桌面 UA），正文提取：`article`/`main` 标签优先，兜底取文本密度最大块；flexmark-html2md 转 Markdown；标题取 `<title>`/`og:title`
- 错误语义：网络/超时 → 422 `FETCH_FAILED`；正文提取为空 → 422 `EXTRACT_FAILED`（提示用户改用粘贴）；非 http/https → 400
- 抓取后 `source_url` 记录原 URL

### FR-003 导入细节

- `.md`/`.txt` 直接读文本；`.pdf` 用 Apache PDFBox 提取文本（保留段落换行）；文件本身存 `UPLOAD_DIR/knowledge/`，`file_path` 落库
- PDF 扫描件（无文本层）提取为空 → 422 `EXTRACT_FAILED`

### FR-004 产物细节

- 总结结构（Markdown）：`## TL;DR`（≤3 句）+ `## 要点`（3–7 条）+ `## 关键洞察`（1–3 条）
- 脑图产物 = Markdown 标题层级大纲（中心主题为 H1，层级 ≤4，节点短句），同一份内容既可喂 markmap 也可直接当文字大纲展示
- 超长处理：LLM 输入截断至 ~100k 字符，产物末尾自动标注「内容过长，基于前 N 字符生成」
- 重试：`POST /api/knowledge/{id}/artifacts/{kind}/regenerate`，重置状态机重新生成

### FR-007 进度细节

- 详情页原文视图滚动容器监听 scroll，2s 防抖 PATCH `progress`（0–100，取滚动百分比）；进入详情若 `progress>0` 自动恢复到对应滚动位置

### FR-008 转入细节

- `POST /api/knowledge/from-inbox {inboxItemId}` 三路分流：内容 trim 后为合法 URL → 走 FR-002 管线；否则条目 `link` 为合法 http(s) URL（DIGEST 条目，URL 存 link、content 只存标题）→ 走 FR-002 管线抓 link（title 抓取自动提取）；否则作为 `content` 直接入库（title 取首行/前 50 字符）
- 成功后调 inbox 既有逻辑标 `readAt`；不删除 inbox 条目

### FR-009/010 细节

- 问答：`POST /api/knowledge/{id}/chat`（SSE，帧协议沿用 `{type:token}/{type:done}`）；system prompt 注入标题+总结+原文（截断）；`conversationId = knowledge-{itemId}` 复用 `chat_message` 表；不挂任何 Tool
- 检索：条目入库/内容更新时异步分块（~1000 字符、重叠 100）+ embedding；启动时检测 `pg_extension` 无 vector 扩展或 embedding 调用失败 → 降级 `LIKE` 关键词检索 + WARN
- 前置条件：本地 PostgreSQL 安装 pgvector 扩展（`CREATE EXTENSION vector`）；embedding 模型默认可配 `text-embedding-3-small`，复用 AI 配置档案的 base-url/key

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-03 | 初稿 | 2.0 立项（全网调研 + 六项关键决策确认） |
| 2026-08-04 | FR-008 转入细节改为三路分流：content 为 URL 走抓取 → 否则 `link` 为合法 http(s) URL（DIGEST 条目）走抓取 → 否则 NOTE | T-007 审查发现 DIGEST 条目 URL 在 link 字段，原逻辑只产出标题壳 NOTE |
