---
status: verified       # draft → approved → verified
feature: digest-structured-output
created: 2026-08-10
---

# 精简规格：Digest 摘要迁移 Spring AI Structured Output（M 级）

## 1. 需求

背景：Digest 2.0 的 summarize/editor 两步目前靠手搓 JSON 解析（剥 ``` 围栏 + Jackson 读 Map + 逐字段取数），脆弱且样板代码多。Spring AI 2.0 的 `CallResponseSpec.entity()` 由 StructuredOutputConverter 自动注入格式指令并把响应绑定为强类型 record，可替换全部手写解析。本次只改输出绑定方式，不改变任何外部行为（缓存、降级链、重试、fallback 文案均不变）。

| 编号 | FR | 验收标准（当…时，系统应…） |
|------|-----|---------------------------|
| FR-001 | `summarize()` 改用 `.entity()` 绑定强类型 record，删除 `parseJson`/剥围栏/Map 取字段代码 | LLM 返回合法输出时产出字段完整的 `ArticleSummary`；返回坏输出时仍重试 1 次、再失败仍返回 RSS 降级摘要（单测验证） |
| FR-002 | `editor()` 同样改用 `.entity()`，嵌套结构映射为强类型 record 后转 `EditorOutput` | LLM 返回合法输出时产出含 sectionLedes 的 `EditorOutput`；异常仍返回 `null`（单测验证） |
| FR-003 | 两个 prompt 移除手写 JSON schema，字段约束改为散文描述，格式指令交给 converter 注入 | prompt 中不再出现 `{` schema 块；`PROMPT_VERSION`（sha256）随文本自动变化 |
| FR-004 | 行为不变式：缓存键、降级链、`MAX_RETRIES=1`、fallback 文案、`EditorOutput` 对外签名不变 | 改造后 `mvn test` 全绿（含重写后的 `SummarizationServiceTest`） |
| FR-005 | 可评测（workflow §7.1）：评测集与阈值不变 | 有真实 `AI_API_KEY` 时 `SummarizationEval` 20 条 golden 仍达阈值（why_it_matters 长度通过率 ≥85%、headline ≥90%、六字段非空 100%） |

**范围外**：OpenAI 原生 `response_format: json_schema` 切换（保持 provider 无关的 converter 路线）；editor 评测脚本（run-editor-judge.py 仍 TBD）；缓存 `prompt_version` 参与缓存键（维持 D4 决策）。

## 2. 方案要点

- 新增输出 record：`LlmArticleSummary(headline, tldr, detail, @JsonProperty("why_it_matters") whyItMatters, source, url)`；editor 侧 `EditorJson(headline, opening, sections)` → `EditorSections(ai, tech, finance, other)` → `EditorSection(lede, articleOrder)`，再映射到现有 `EditorOutput`/`DigestCategory`（避免 Map<enum> 的 schema 生成坑）。
- `callLlm(prompt)` 改为 `callLlm(prompt, Class<T>)` 返回 `T`：`.call().entity(type)`，30s 超时与重试逻辑保持原样（重试时仍追加一句 schema 提醒）。
- prompt 只留角色 + 输入 + 字段要求散文；`PROMPT_VERSION = sha256(PROMPT_SUMMARIZE)` 机制不动。
- 无表结构/接口/依赖变更（entity() 为 Spring AI 核心自带，converter 走 BeanOutputConverter 提示词路线，对 Kimi 等 OpenAI 兼容端点同样有效）。
- 评测方式：`SummarizationEval` 入口不变（service.summarize 签名不变），阈值不变，作为本改动的回归门禁。

## 3. 任务

| ✓ | 任务 | 验收点 | Commit |
|---|------|--------|--------|
| ☑ | 新增 `LlmArticleSummary` record + `summarize()` 改 `.entity()`，删 parseJson 相关代码 | 单测：合法/坏输出/异常三路径正确 ✅ | 28930f7 |
| ☑ | 新增 editor 输出 record + `editor()` 改 `.entity()` + 映射 `EditorOutput` | 单测：成功/失败两路径正确 ✅ | 28930f7 |
| ☑ | 简化 PROMPT_SUMMARIZE / PROMPT_EDITOR（去手写 schema） | prompt 无输出 schema 块，PROMPT_VERSION 随文本变化 ✅ | 28930f7 |
| ☑ | 重写 `SummarizationServiceTest`（mock `entity()`） | 7 个用例全绿 ✅ | 28930f7 |
| ☑ | 全量 `mvn test` + 跑 `SummarizationEval` | 172 全绿；eval 20 条：why_it_matters 1.00、headline 0.90 ✅ | 28930f7 |

## 4. 验收记录

| FR | 结果 | 验证方式 |
|----|------|----------|
| FR-001 | 通过 | `summarize_llmSuccess_bindsEntityAndCaches` / `summarize_llmOutputUnparseable_retriesOnceThenFallbacks` / `summarize_llmThrows_returnsFallback` |
| FR-002 | 通过 | `editor_llmSuccess_returnsEditorOutput` / `editor_llmFailure_returnsNull` |
| FR-003 | 通过 | prompt 仅留角色+输入+字段散文要求；`PROMPT_VERSION=sha256(PROMPT_SUMMARIZE)` 机制未动 |
| FR-004 | 通过 | `mvn test -pl axis-service` 172 个测试全绿（含重写后 7 个 SummarizationServiceTest 用例 + null 字段默认值新用例） |
| FR-005 | 通过 | 真实 key 跑 `SummarizationEval`：total=20，why_it_matters 通过率 1.00（≥0.85）、headline 0.90（≥0.90） |

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-10 | 修复 editor prompt 潜伏缺陷：旧 prompt 中不存在 `{sections}` 占位符，`replace()` 为 no-op，editor 从未收到真实文章数据；新 prompt 显式放入 `{sections}` | 实现中发现的缺陷修复（豁免清单），不修则 editor pass 一直在无输入下幻觉输出 |
| 2026-08-10 | 修复 `SummarizationEval` 潜伏缺陷：golden 文件是 JSONL（每行一个对象），harness 却按 JSON 数组解析，有真实 key 时必崩；改为逐行解析 | 同上，缺陷修复；不修则 FR-005 无法验证 |
| 2026-08-10 | LLM 输出契约字段命名由 snake_case（`why_it_matters`）改为 camelCase（`whyItMatters`） | Spring AI 2.0 底层是 Jackson 3（`tools.jackson`），schema 生成（victools）与反序列化分属两套注解体系；统一 camelCase 规避 `@JsonProperty` 版本错配风险。仅影响 LLM 输出契约，落库 shape 不变 |
| 2026-08-12 | `MAX_RETRIES` 1 → 2（用户侧调整，随本特性 commit 合入）；单测重试次数断言对齐 | 提交前未重跑测试导致的红灯教训：以后 commit 前必须再跑一次全量测试 |
