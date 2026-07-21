# 项目宪章（Constitution）

> 全局约束，写一次长期生效。AI 任何阶段都不得违反；要改本文件必须人类显式同意。
> 以下为示例内容，请按你的项目实际替换。

## 1. 技术栈基线（固定，不得擅自变更）

| 层 | 选型 | 备注 |
|----|------|------|
| 前端 | Vue 3 + TypeScript + Vite + Pinia + Vue Router | UI 库：xxx |
| 后端 | Spring Boot 3 + Java 17 + MyBatis-Plus（或 JPA） | 构建：Maven |
| 数据库 | MySQL 8 | 迁移：Flyway / 手写 migration |
| 其他 | Redis（缓存）、JWT（认证） | |

**新增任何三方依赖前必须先提议并获得确认。**

## 2. 代码约定

- 前端：组合式 API + `<script setup>`；组件名 PascalCase；接口请求统一走 `src/api/`
- 后端：分层 Controller / Service / Mapper；统一返回体 `Result<T>`；统一异常处理 `@RestControllerAdvice`
- 命名 / 目录 / 提交信息（Conventional Commits）约定：……

## 3. 禁止事项

- 不得修改已发布的 migration 历史文件，只能新增
- 不得在未确认的情况下变更公共接口的既有契约
- 不得提交未过验收点（验收标准 / 任务验收点）的代码
- 不得“顺手”做任务表之外的重构（可提议，不直接做）
- 不得让 Agent 在无 max 步数 / 无超时 / 无重试上限的情况下运行
- 不得让 Agent 未经人工确认执行不可逆操作（删改文件、外发请求、支付）
- 每次 LLM 调用必须留 trace（模型、输入摘要、输出、token 数、耗时）
- 不得提交真实 API Key / Token

## 4. AI 工作规则（文档流强制条款）

1. 开工前必读：`docs/workflow.md` + 本文件 + 进行中功能的 01/02/03
2. 新需求先按 workflow 分级；L 级走完三道门禁才可编码
3. 实现时严格按 03 任务表逐任务执行：完成一个 → 打勾 → 填 commit → 下一个
4. 对已批准文档的任何偏离：停下 → 写「变更记录」→ 等确认
5. 文档风格：简洁，优先 Mermaid 图和表格，避免大段文字
