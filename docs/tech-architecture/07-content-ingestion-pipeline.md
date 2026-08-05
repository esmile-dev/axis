---
title: 摄取管线设计：正文提取、文件解析与错误语义分类学
slug: content-ingestion-pipeline
description: 三条摄取管线（粘贴/URL/文件）的工程细节——纯函数防腐层、readability 务实简化版（语义标签+文本密度）、先解析后落盘、multipart 限额的 framing 余量，以及 HTTP 错误码分类学：400/404/413/415/422 各携带「错在哪一层」的信息，驱动前端精确引导。
status: knowledge
tags: [web-scraping, readability, jsoup, pdfbox, error-handling, http-status-codes, anti-corruption-layer, interview]
created: 2026-08-04
---

# 摄取管线设计：正文提取、文件解析与错误语义分类学

> 三条管线形态各异，终点只有一个——归一化的 Markdown content。本文讲每条的工程细节与贯穿其中的设计纪律。

---

## 1. 全局结构与最重要的结构决策

```
粘贴   ──────────────────────────────┐
URL    → WebPageFetcher(薄网络层)     │
         → ArticleExtractor(纯函数)   ├→ KnowledgeItem(content=Markdown)
文件   → ImportedFileParser(纯函数)   │   → 同一事件 → 同一下游
         → KnowledgeFileStorage(落盘) ┘
```

**核心逻辑全部是无副作用的纯函数**（`String in → record out`），网络/IO 推到薄壳。回报是测试质量：extractor 9 条测试用真实 HTML fixture 断言产物，零 mock——mock 网络层永远测不到「提取的正文干不干净」。

## 2. URL 抓取：readability 的务实简化版

**正文提取三级策略**（`ArticleExtractor`）：

1. `article`/`main` 语义标签优先（有测试专门钉住「别的块更长时 main 仍赢」）
2. 兜底：文本密度评分（段落文本长/标签数比值）选最大块——导航栏链接多文本少，密度天然低
3. 提取为空不硬猜 → `EXTRACT_FAILED` 引导用户改用粘贴

**转 Markdown**（flexmark-html2md）：关闭 setext 标题强制 ATX（`#` 式）——下游脑图与评测都要解析标题层级，**转换规则是为下游消费者定的**。

**诚实的边界**（面试主动讲）：

- Jsoup 不执行 JavaScript——SPA/强 JS 站点抓回来是壳；方案级限制，产品上有 422→粘贴的逃生口，将来要解得上 headless browser
- jsoup 默认 2MB maxBodySize 可能截断超长 HTML（记录在案）
- 密度兜底只统计 `<p>`，纯列表/表格正文会退化（10 篇真实文章评测全过，够用）

## 3. 文件导入：顺序与尺寸

- **先解析后落盘**：415/422 不产生孤儿文件（测试用 `verifyNoInteractions(storage)` 真实验证失败路径不碰存储）；顺序反了就要写补偿删除
- **multipart 限额的 framing 余量**：`max-file-size: 20MB` / `max-request-size: 21MB`——request 多留 1MB 给 multipart 的 boundary/headers，保证「恰好 20MB 的文件」能过。**5% 余量思维适用一切限额设计**：队列、buffer、超时
- **PDF 边界**：PDFBox 抽文本层；扫描件无文本层 → `EXTRACT_FAILED`（OCR 明确出范围）；测试 fixture 用 PDFBox 现场生成真 PDF 再真解析
- **存储安全**：文件名全新 UUID，扩展名在白名单收敛后才到达存储层——注入/路径穿越无向量

## 4. 错误语义分类学：状态码携带「错在哪一层」

| 码 | 语义 | 例子 | 前端引导 |
|---|---|---|---|
| 400 | 请求本身不合法（Bean Validation） | 空 URL、非 http(s) | 改输入 |
| 404 | 资源不存在 | itemId 错 | 刷新列表 |
| 413 | 超过尺寸 | >20MB | 「文件超过大小限制」 |
| 415 | 格式不支持 | `.exe` | 「仅支持 md/txt/pdf」 |
| 422 | 请求合法但语义处理失败 | 抓取失败/正文为空 | 区分原因串再给引导 |

422 层的关键设计：`FETCH_FAILED`（网络/超时，值得重试）与 `EXTRACT_FAILED`（提到空，重试无意义该换粘贴）是**两种不同用户行动**，reason 串带机器可读前缀，前端精确提示而非一句「出错了」。**错误码驱动用户引导，不只是日志分类。**

## 5. 配置实证方法论（Boot 大版本升级期保命）

两个真实踩坑：

- Boot 4 把 `server.error.*` 迁到 `spring.web.error.*`，**旧键静默忽略不报错**——不配 `include-message: always`，422 的 reason 串根本到不了前端
- multipart 键名在主 autoconfigure jar 里根本没有，位于 `spring-boot-servlet.jar`

可靠解法：不看博客不猜记忆，直接解 jar 里的 `META-INF/spring-configuration-metadata.json` 查真实键名与 deprecation 记录。

## 6. 面试问答速记

**Q：网页正文提取怎么做？**
A：语义标签优先+文本密度兜底，转 Markdown 入库。主动讲边界：不执行 JS，SPA 有逃生口；完整 readability 规则集是已知演进方向。

**Q：错误码怎么设计？**
A：400 输入/404 不存在/413 尺寸/415 格式/422 语义失败；422 再用机器可读原因串细分（FETCH vs EXTRACT），因为两类失败的用户行动不同。

**Q：上传怎么防滥用？**
A：扩展名白名单、双层限额（讲 framing 余量）、先解析后落盘无孤儿、UUID 存储名防穿越。

**Q：为什么解析放后端？**
A：抓取有 CORS；三条管线在后端归一化，前端零解析负担；PDF 解析前端无等价库。

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-04 | 初稿 | 知识库 2.0 复盘系列第 3 讲沉淀 |
