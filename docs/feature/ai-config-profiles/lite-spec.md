---
status: verified     # draft → approved → verified
feature: ai-config-profiles
created: 2026-07-24
---

# 精简规格：AI 配置多档案管理（M 级）

## 1. 需求

**背景**：digest-2.0 已实现 AI 配置的 DB 持久化与运行时热加载，但 `app_config` 采用扁平 key，前端只能看到并编辑一套生效配置。希望 settings 页能管理多套提供商配置，运行时仅一套生效。

| 编号 | FR | 验收标准 |
|------|-----|----------|
| FR-001 | 后端支持多套 AI 配置档案 | `GET /api/v1/config/ai/profiles` 返回 ≥1 条 |
| FR-002 | 运行时只有一套 active 生效 | 切换 active 后，下次 LLM 调用使用新 `ChatClient` |
| FR-003 | 前端以列表管理档案 | 可新增/编辑/删除/测连/设 active |
| FR-004 | 兼容旧数据 | 启动时自动将 digest-2.0 的 `app_config` 扁平配置迁移为首个 profile |

**范围外**：
- 不同模块绑定不同模型（digest/chat 仍共用同一 active profile）。
- 团队/多租户隔离。

## 2. 方案要点

**数据模型**：新增 `ai_config_profile` 表，字段 id / name / api_key(加密) / endpoint / model / is_active / created_at / updated_at。

**关键接口**：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/config/ai/profiles` | 列表，apiKey 掩码 |
| POST | `/api/v1/config/ai/profiles` | 新建；无 active 时自动 active |
| PUT | `/api/v1/config/ai/profiles/{id}` | 更新；空 apiKey 表示保持原值 |
| DELETE | `/api/v1/config/ai/profiles/{id}` | 禁止删唯一 active |
| POST | `/api/v1/config/ai/profiles/{id}/activate` | 设 active 并 reload |
| POST | `/api/v1/config/ai/profiles/{id}/test` | 单条测连 |
| GET | `/api/v1/config/ai/active` | 当前生效档案 |
| POST | `/api/v1/config/ai/reload` | 重新加载 active profile |

**UI**：settings.vue 从单表单改为卡片列表 + Add/Edit Modal；active 卡片高亮；删除唯一 active 时阻止。

## 3. 任务

| ✓ | 任务 | 验收点 | Commit |
|---|------|--------|--------|
| ☑ | 新增 `AiConfigProfile` 实体 + Repository + DDL | `\d ai_config_profile` 表存在 | |
| ☑ | `AiConfigService` 支持 profile 加载/保存/激活；启动迁移旧配置 | 旧数据启动后生成 active profile | |
| ☑ | 新增 profile CRUD + activate/test 端点 | 6 个新端点 curl 200 | |
| ☑ | 保留原 `/api/v1/config/ai/*` 兼容映射到 active profile | 原接口行为不变 | |
| ☑ | settings.vue 改列表布局 + Add/Edit Modal | 页面可见多条卡片 | |
| ☑ | 前端绑定 profile CRUD API | 增删改后列表刷新 | |
| ☑ | 前端绑定 activate / test / delete | 切换/测连/删除反馈正确 | |
| ☑ | 后端单测覆盖 service + controller | `mvn test` 新增用例绿 | |
| ☑ | 端到端验证：2 个 profile 切换 + digest 调用 | 切换后调用走新模型 | |
| ☑ | 更新 `.env.example` / `application.yml` 注释 | 无破坏性提示 | |
| ☑ | 写 `summary.md` 复盘 | 归档 | |

## 4. 验收记录

| FR | 结果 | 验证方式 |
|----|------|----------|
| FR-001 | ✓ | `GET /api/v1/config/ai/profiles` 返回多档案；curl 200 |
| FR-002 | ✓ | 切换 active 后 `/api/v1/config/ai` 立即返回新配置 |
| FR-003 | ✓ | settings.vue 列表可见；新增/编辑/删除/切换操作正常 |
| FR-004 | ✓ | 启动时旧 `app_config` 扁平 key 自动迁移（已测试） |

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-07-24 | 初稿 | AI 提议 M 级，待用户确认 |
