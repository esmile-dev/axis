---
status: done
feature: digest-2.0
created: 2026-07-22
---

# 复盘总结：信息摘要 2.0（Daily Digest 2.0-MVP）

## 验收结论

G4 通过。关键验证：
- 正常路径：settings 页保存 DeepSeek 配置 → reload → 触发 digest → inbox 出现 `aiGenerated=true` 的扩展文章数组，`llmCallCount=14`（13 篇精读 + 1 次主编），`summary` 含"AI 摘要"。
- 降级路径：错误 API key → digest 仍 `COMPLETED`，`summary` 含"AI 摘要暂不可用，已降级"，`longText` 保持前端兼容数组格式。
- 配置持久化：`app_config` 表加密存储 API key，DB 优先/env 兜底，运行时 reload 无需重启。

## 做得好的 / 可复用经验

1. **热更新 ChatClient**：`AiConfigService` 持 `volatile ChatClient`，settings 页 reload 后全局生效，digest 与 chat agent 共用同一配置源，避免两套配置漂移。
2. **失败降级优先保证可用**：单篇 LLM 失败回退 description，整批失败回退 keyword 分类版本，digest 流程不会空跑。
3. **按 link 缓存降本**：`article_summary_cache` 使同日内多次 cron/手动重跑不重复计费。
4. **前端兼容的 longText**：LLM 成功时仍输出数组格式并附加新字段，现有 Inbox 卡片无需改动即可渲染。

## 踩坑记录

1. **ChatClient bean 冲突**：`axis-agent` 原本有 `ChatClient` `@Bean`，`axis-service` 引入 spring-ai 后又触发 `OpenAiChatAutoConfiguration`。解决：删除 `AiConfig.java` 的 bean，改注入 `AiConfigService.get()`；`AxisApplication` 排除 `OpenAiChatAutoConfiguration`。
2. **SummarizationService 注入 ChatClient 导致 reload 失效**：初版注入 `ChatClient` bean，reload 后该 bean 引用不变。修复为注入 `AiConfigService` 每次调用 `.get()`。
3. **新增 `llm_call_count` 列导致旧数据启动失败**：列设 `nullable=false` 但旧行无值。修复为 `Integer` + 默认 null 兼容旧数据。
4. **Spring Boot 4.0 `@WebMvcTest` 缺失**：当前版本 spring-boot-test-autoconfigure 无 `WebMvcTest`，改用 `MockMvcBuilders.standaloneSetup` 测 controller。
5. **editor prompt 对轻量模型输出不稳**：DeepSeek `deepseek-v4-flash` 在 editor 复杂 prompt 上偶发不输出 JSON；后续 2.1 可考虑针对轻量模型精简 editor prompt 或换更强模型。

## 衍生需求 / 后续优化

- **2.1 源扩展**：arXiv / HN / Google News RSS / 24h 窗口 / hash 去重。
- **2.2 推送与存档**：日报详情页、邮件/TG 推送、按天回看。
- **2.3 高级**：跨源事件合并（embedding 聚类）、👍/👎 反馈、cost 仪表盘。
- **当前 prompt 优化**：针对 cheap 模型进一步压缩 editor prompt，提高 JSON 输出稳定性。
