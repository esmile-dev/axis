---
title: AI 配置的 API Key 生命周期：从 Settings 页面到 LLM 提供商
slug: ai-config-api-key-lifecycle
description: 讲清 Settings 页设置的 API key 如何加密存储、如何被解析成全局唯一的 ChatClient、聊天与 Digest 如何共用它，以及一次 Deepseek 401 报错的完整诊断过程——为什么管线没坏，坏的是 key 本身。
status: knowledge
tags: [spring-ai, ai-config, api-key, aes, chatclient, openai-compatible, troubleshooting]
created: 2026-07-26
---

# AI 配置的 API Key 生命周期：从 Settings 页面到 LLM 提供商

> 以一次真实报错（`UnauthorizedException: 401: Authentication Fails, Your api key: ****31d9 is invalid`）为线索，讲清本项目的 AI key 是怎么存的、怎么用的、怎么排查的。

---

## 1. 全景：一条 key 的旅程

```
Settings 页 (profiles CRUD)
   │  POST /api/v1/config/ai/profiles  {name, apiKey, endpoint, model}
   ▼
AiConfigService.createProfile()          backend/backend/src/main/java/com/esmile/axis/llm/AiConfigService.java:112
   │  AES-256 加密 apiKey（Encryptors.delux(password, salt)）
   ▼
ai_config_profile 表（DB，密文存储，isActive 标记唯一激活项）
   │  启动 @PostConstruct load() / 激活时 reload()
   ▼
decrypt → buildClient(apiKey, endpoint, model)          AiConfigService.java:269
   │  OpenAiChatModel（OpenAI 兼容协议，Deepseek/任意兼容端点均可接）
   ▼
全局唯一 ChatClient（volatile，原子热切换）
   ├── AgentController（/api/agent/chat、/chat/sync、/expand）
   └── Daily Digest SummarizationService
   ▼
HTTP 请求：{endpoint}/chat/completions，Header: Authorization: Bearer <解密后的 key>
   ▼
LLM 提供商网关鉴定 → 通过则返回补全；拒绝则 401
```

**核心设计**：全应用只有一个激活 profile、一个 `ChatClient`。聊天 Agent 和 Daily Digest 共用同一实例，避免两套配置漂移（见 `docs/feature/digest-2.0/design.md`）。

## 2. 存储：加密落库，明文不出后端

- **表**：`ai_config_profile`（id / name / apiKey(密文) / endpoint / model / isActive），同一时刻只有一行 `isActive=true`。
- **加密**：AES-256，`Encryptors.delux(encryptionPassword, encryptionSalt)`（`AiConfigService.java:294`）。口令来自环境变量 `AXIS_ENCRYPTION_PASSWORD` / `AXIS_ENCRYPTION_SALT`，缺省为开发默认值（生产必须覆盖）。
- **展示**：任何接口返回的 key 都走 `maskedApiKey()`（`AiConfigService.java:67`），形如 `sk-e***31d9`。明文只在构建 ChatClient 的瞬间存在于内存。
- **启动解析顺序**（`loadFromDb()`，`AiConfigService.java:210`）：
  1. DB 中 `isActive=true` 的 profile（解密失败会记日志并 fall through，常见原因是换过加密口令）
  2. 遗留 `app_config` 表的 `ai.api_key/endpoint/model`（Digest 2.0 旧配置，首次启动自动迁移成 profile 并删旧键）
  3. `.env` 的 `AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL` 兜底

  ⚠️ **只要 DB 里有激活 profile，`.env` 的 key 就完全不会被使用**——改 `.env` 不生效是最常见的误解。

## 3. 使用：两处消费，一处构建

| 消费方 | 入口 | 说明 |
|---|---|---|
| 聊天 Agent | `backend/src/main/java/com/esmile/axis/chat/AgentController.java` | `aiConfigService.get()` 取 client，挂 ChatMemory advisor + 5 个 Tool |
| Daily Digest | `backend/src/main/java/com/esmile/axis/digest/summarize/SummarizationService.java` | RSS 文章摘要 |

Controller 侧**不注入 ChatClient Bean**，而是注入 `AiConfigService` 调 `.get()`——因为 client 会在「激活/编辑 profile」时被 `reload()` 原子替换（`volatile currentClient`），直接注入拿到的会是启动时的旧实例。`AxisApplication` 也排除了 `OpenAiChatAutoConfiguration`，防止 Spring AI 自动配置再造一个 client 打架。

新增/编辑/激活/删除 profile 后都会自动 `reload()`，**改配置不需要重启应用**。

## 4. 案例：401 报错的三层定位

现象：聊天请求 500，后端日志：

```
com.openai.errors.UnauthorizedException: 401: Authentication Fails, Your api key: ****31d9 is invalid
```

**第一层：报错是谁产生的？** 不是 Spring、不是后端代码——是 LLM 提供商网关返回的 401，openai-java 客户端把错误体映射成 `UnauthorizedException`。后端只是原样上抛。

**第二层：用的是哪把 key？** 报错里的 `****31d9` 与 Settings 页显示的 `sk-e***31d9` 后缀一致 → 发出去的就是 DB 激活 profile 里那把 key。这一步同时证明：**解密、构建 client、注入、发送，整条管线是通的**。

**第三层：为什么被拒？** Deepseek 的语义：401 = key 无效（欠费是 402，模型名错误是 404/400）。可能原因按概率排：

1. key 被吊销 / 在控制台重新生成过（旧 key 作废）
2. 复制时多了空格、缺字符
3. **key 与 endpoint 不配套**：各家 key 都是 `sk-` 开头，把 OpenAI 的 key 填进 `api.deepseek.com` 的 profile 就会得到这个错

## 5. 排查与修复手册

```bash
# 1. 看当前生效配置（来源 db 还是 env、endpoint、model、脱敏 key）
curl -s localhost:7789/api/v1/config/ai

# 2. 看所有 profile（哪把是 isActive）
curl -s localhost:7789/api/v1/config/ai/profiles

# 3. 对某个 profile 做真实连通性测试（会真发一条 "pong" 请求）
curl -s -X POST localhost:7789/api/v1/config/ai/profiles/<id>/test
```

修复路径：去对应平台控制台确认/重新生成 key → Settings 页编辑激活中的 profile 填入 → 自动 reload → 点「测试」通过后再用。

两个容易踩的坑：

- **改了 `.env` 没效果**：DB 有激活 profile 时 env 只是兜底，永远不会被读到（见 §2）。
- **换 key 后报 404/400 而不是 401**：鉴权过了但模型名不对。profile 里的 model 必须是该平台真实存在的名字（如 Deepseek 官方是 `deepseek-chat` / `deepseek-reasoner`），自定义拼写会被网关拒绝。

---

## 参考代码

- `backend/src/main/java/com/esmile/axis/llm/AiConfigService.java` — 全部逻辑（存储/加密/解析/热切换/测试）
- `backend/src/main/java/com/esmile/axis/llm/ConfigController.java` — REST 入口 `/api/v1/config/ai*`
- `backend/src/main/java/com/esmile/axis/AxisApplication.java` — 排除 OpenAI 自动配置
- `docs/feature/ai-config-profiles/lite-spec.md` — 多 profile 功能的需求与设计
