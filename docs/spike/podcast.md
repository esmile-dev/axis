---
name: podcast
description: 在 Axis 新增 Podcast 栏目：音频输入（上传/URL/RSS）→ STT 转写 → LLM 总结 → 可追问。本 spike 收敛 STT 选型与 Knowledge 集成方案。
status: draft
---

# Spike: Podcast 栏目

## 1. 背景与边界

Axis 现有知识管理覆盖文字（Knowledge 笔记）+ 文字灵感（Inbox）+ 任务（Issue）。播客是日常高频摄入源，但缺乏结构化沉淀路径。本 spike 评估新增「Podcast」栏目的可行性，**不产出代码**，仅收敛两个未决项：**STT 选型** 和 **Knowledge 集成深度**。

## 2. 已决定的产品边界

| 维度 | 决策 |
|---|---|
| 输入方式 | 本地文件上传 + URL 下载 + RSS 定期订阅（一次全做） |
| 总结形态 | 整体摘要 + 关键观点 + 可追问对话 |
| 转写原文 | 保留（追问与未来搜索依赖） |
| STT 服务 | 待定（见 §3） |
| Knowledge 集成 | 待定（见 §4） |

## 3. STT 服务对比

| 候选 | 中文 | 英文 | 1h 成本 | 外发 | 接入 | 备注 |
|---|---|---|---|---|---|---|
| **OpenAI Whisper API** | ★★★★ | ★★★★★ | ~$0.36 ≈ ¥2.6 | 是 | 复用 axis-agent 现有凭据，零签约 | **推荐** |
| 国内云（豆包/讯飞/阿里）| ★★★★★ | ★★★ | ~¥0.5 | 是 | 需新签合同/密钥 | 中文播客更优 |
| 本地 whisper.cpp | ★★★★ | ★★★★ | 0 | 否 | 部署复杂，1h 音频 ~20min CPU | 隐私敏感场景 |
| AssemblyAI / Deepgram | ★★★★ | ★★★★★ | $0.4-0.5 | 是 | 海外信用卡+新合同 | 行业最强 |

**推荐：OpenAI Whisper API**。理由：
1. 复用 axis-agent 已有的 OpenAI 凭据与 `base-url`，零额外签约
2. 质量/成本平衡最好
3. 接口简单（`POST /v1/audio/transcriptions`），不引入新 SDK
4. 中文/英文均够用；中播客比例上升可切豆包，STT 接口在 `PodcastTranscriptionService` 内部隔离

## 4. Knowledge 集成方案

| 方案 | 改动量 | 体验 | 评级 |
|---|---|---|---|
| A. 独立表 + 独立 tab | 小（M 级）| 搜索要分别查；不能关联 Issue | ★★ |
| **B. 总结可一键存档为 Knowledge 笔记** | 中 | 复用搜索/标签/Issue 关联 | ★★★★ |
| C. 完全共用 Knowledge 模型 | 大（L 级）| 体验最好，侵入性强 | ★★★★★ |

**推荐 B**：Podcast 独立存（`podcast_episode` 表 + 转写原文 + 总结），但提供「存档为笔记」动作，把总结写到现有 `knowledge` 表。改造成本可控、复用搜索/标签/Issue 关联。

## 5. 模块归属

- **axis-service**：`PodcastController` / `PodcastService` / `PodcastRepository` / `PodcastEpisode` 实体 + 音频文件存储（`./uploads/podcast/`，复用 `UPLOAD_DIR`）+ RSS 调度器（每日一次，命中 `last_pulled_at`）
- **axis-agent**：`PodcastSummaryService`（整体摘要 + 关键观点）/ `PodcastChatService`（追问，复用 `ChatClient` + `ChatMemory`，按 `episodeId` 隔离会话）
- **新表**：`podcast_episode`（元信息 + 转写原文 + 总结 + 状态机：uploaded/transcribing/summarizing/ready/failed）

## 6. 关键风险

1. **长音频超时**：Whisper API 单文件 ≤25MB，超长需后端切分（按静音切片）或前端预压缩
2. **URL 反爬**：Apple Podcasts / 小宇宙有 UA 校验，`PodcastDownloader` 需伪装 UA + 失败重试
3. **RSS 去重**：订阅源持久化 `last_pulled_at` + episode guid 去重，否则重复转写烧钱
4. **追问成本**：每次追问 = LLM 调用 + 转写原文 token 消耗；建议限制上下文窗口（如最近 5 轮对话 + 摘要）
5. **追问会话膨胀**：转写文本常达数万字，**不能**整段塞 prompt；追问时按"摘要 + 检索切片"组合喂给 LLM（具体策略在 design 阶段定）

## 7. 转正路径

本 spike 完成后按 **L 级** 走完整 feature 流程（`requirements.md` → `design.md` → `tasks.md`）。建议实施顺序：

1. **M1（核心闭环）**：本地文件上传 → STT 转写 → 整体摘要 → 列表/详情展示
2. **M2（追问）**：复用 ChatClient，支持对单集追问；成本控制上线
3. **M3（URL + RSS）**：URL 下载器 + RSS 调度器 + 去重
4. **M4（Knowledge 存档）**：总结一键存档为笔记

## 8. 探索性结论

- **可行性**：明确可行。复用现有 OpenAI 凭据、`ChatClient`、`FileUploadController`、`UPLOAD_DIR`；新增工作量集中在 RSS 调度与 URL 解析
- **不建议先做的事**：多说话人分离（diarization）、自动章节切分、批量上传 —— 等 M1 跑通再评估
- **不建议绕过的事**：转写原文必须保留（追问依赖）、STT 锁定 OpenAI（成本/质量平衡）、`PodcastTranscriptionService` 必须抽象接口（未来切豆包无需动业务层）