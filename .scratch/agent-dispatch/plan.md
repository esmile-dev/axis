---
status: draft            # draft → approved →（Phase 1 完成后）verified
feature: agent-dispatch
created: 2026-08-21
---

# 分期计划：从 Axis 派任务给本地 Coding Agent

调研事实与能力矩阵见 spike：`docs/spike/assign-task-to-local-coding-agents.md`（本文件不重复，只做分期决策）。

## 1. 背景与目标

Axis 今天管的是任务的生命周期（BACKLOG → DONE 靠人拖），执行环节在系统外。本 feature 把执行接进来：从 Issue 详情页把任务交给本地 coding agent（Claude Code），agent 干完后**自己回写**完成汇报与状态——Axis 从"记录任务的系统"变成"派发并回收任务的系统"。

**集成哲学（2026-08-21 确立）：不编排 agent，而是交接 + 给 agent 工具回写。** Axis 不替代 Claude 这类 coding agent，交互界面留在终端里（人全程在场，human-in-the-loop 原生免费）；Axis 提供"交接时的上下文打包"和"干完后的结构化回写通道"。这是 Linear / Vibe Kanban 的 MCP server 同款轻量模式。重量级的编排方案（spawn headless CLI + 事件流解析）保留为 Phase 2 候选，视实际使用反馈决定做不做。

## 2. 决策记录（2026-08-21）

| 决策点 | 结论 | 理由与代价 |
|--------|------|-----------|
| Issue 关联代码目录 | **Project 加 `repo_path` 可空字段**，Issue 继承；未配置的 Project 不显示派发入口 | agent 的 cwd 决定在哪改代码 + 会话文件归属；两个方案通用 |
| 集成路线 | **启动器交接 + MCP 回写**（Phase 0/1）；spawn 编排降为 Phase 2 候选 | 人在交互式会话里，权限审批/插话/急停全原生；复杂度塌缩。代价：进度不进 UI、无执行记录表（由 MCP 回写的评论替代） |
| Agent 范围 | **Claude Code**（原生支持 MCP，交互式工具审批人在场） | 其他 agent 的 MCP 支持情况届时核实，不预设 |
| 隔离边界 | 真实工作目录，随人手动用 Claude 的既有习惯 | worktree 自动化闭环是编排方案的概念，本路线下由人自管 git |
| 回写语义 | agent 只写**完成汇报评论** + 限定范围的状态流转；**DONE/CANCELLED 永远人拖** | 回调是 best-effort（模型可能忘、终端可能被提前关），终态把关必须在人手里 |
| MCP 工具面 | 只读 + 评论 + 限定状态流转；**删除类/批量写永不暴露** | agent 带写权限读 issue 内容，injection 防线靠收敛半径 + 终端里的人 |

## 3. 分期总览

| Phase | 内容 | 工作量 | 状态 |
|-------|------|--------|------|
| **0** | 启动器交接：Issue → 终端打开 Claude，携带上下文 | ~1 天 | ✅ 已完成（2026-08-21，spec 已 verified） |
| **1** | MCP server + 回写闭环（顺带完成 roadmap #9） | ~2~3 天 | 本期 |
| 2 | 编排 MVP（spawn headless + 进度进 UI + 执行记录表） | 4~6 天 | 候选，视 0/1 反馈再议 |

## 4. Phase 0：启动器交接

**一句话**：Issue 详情页点"在终端派给 Claude" → 本机终端打开、cwd = `repo_path`、Claude 启动且初始上下文含 issue 标题与描述。

### 范围内

- `project` 加 `repo_path`（可空，本地绝对路径，服务端校验目录存在）
- 后端派发端点：组装 prompt（issue 标题 + 描述）→ macOS 打开终端并启动 `claude`（osascript / `open -a`，Terminal/iTerm 兼容性实现期定；长 prompt 命令行转义或落临时文件引用，实现期定）
- 前端：Issue 详情页按钮（仅 `repo_path` 已配时显示）+ Project 设置加 `repo_path` 输入框
- 状态流转：维持现状全手动

### 明确不做

执行记录表、UI 内进度、任何形式的 spawn/输出解析编排。

### 验收

1. 按钮仅在 `repo_path` 已配的 Project 的 Issue 上显示
2. 点击后终端打开、cwd 正确、Claude 启动、初始上下文完整可读
3. 目录不存在时后端拒绝派发
4. `mvn test` 绿 + `npm run build` 过

## 5. Phase 1：MCP server + 回写闭环

**一句话**：Axis 把收敛后的 Issue 操作暴露为 MCP server；派发 prompt 带上"完成后用 axis 工具回写"的指令；agent 干完自己写完成汇报评论、迁状态，人审完拖 DONE。

### 范围内

- Spring AI MCP server（webmvc starter）挂现有 7789，工具面：
  - `get_issue(id)`：读 issue 详情
  - `add_issue_comment(id, content)`：完成汇报（改动摘要、跑了什么测试、遗留问题）
  - `transition_issue_status(id, status)`：目标状态限定 `BACKLOG`/`TODO`/`IN_PROGRESS`；`DONE`/`CANCELLED` 经 MCP 不可达
- 派发 prompt 模板升级：issue 上下文 + 回写指令（完成后 `add_issue_comment` 汇报，可迁 `IN_PROGRESS`）
- 注册文档：`claude mcp add --scope user` 一次注册，所有仓库可用，不污染目标仓库
- best-effort 语义：agent 不回调不留任何脏状态（Issue 状态本来就没被系统改过）

### 明确不做

- Stop hook 确定性回调（Claude Code 的硬手段，但配置要落进目标仓库 `.claude/settings.json`，跨仓库有污染成本——best-effort 不够用时的后备）
- 删除类 / 批量写工具（永不暴露）
- MCP auth（127.0.0.1 单用户本地场景；如需可后加简单 token）

### 验收

1. `claude mcp list` 可见 axis server；终端会话中 agent 可调用 `get_issue` / `add_issue_comment`
2. 完整链路：UI 派发 → 终端干活 → agent 回写评论出现在 Issue 下（含改动摘要）
3. `DONE`/`CANCELLED` 经 MCP 流转被拒
4. `mvn test` 绿 + `npm run build` 过

### 安全

- agent 读 issue 内容 = 潜在不可信内容携带 Axis 写权限：工具面收敛（无删除/无批量）+ 终端里每次 MCP 调用默认需人批准，双保险
- server 绑 127.0.0.1（既有配置）

## 6. Phase 2（候选）：编排 MVP

**做之前要回答**：Phase 0/1 实际使用中，"进度不在 UI 里、干完没有结构化执行记录"是否真实作痛？派发频率够不够高？痛感不足就不做——Phase 0/1 即本 feature 终点。

若做，原方案骨架（详见 git 历史中的初版 plan）：

- spawn `claude -p --output-format stream-json`（路线 A）+ NDJSON 解析 + 规范化事件 → SSE 进 UI
- `agent_execution` 表（session_id / 状态机 / 原始日志落盘）；`--max-turns` 兜底、中止（kill 进程组）、启动时孤儿清扫
- 全程白名单档位（`acceptEdits` + `--allowedTools`）；执行中人机分工 = 前置定策略 + 旁观 + 急停（初版 plan §4.3 的失败路径表）
- 后续候选（worktree 隔离 + diff 闭环、session resume 打回重做、断线回放、逐工具审批回调、成本统计）全部挂在 Phase 2 之下，编排不做则均不成立

## 7. 与启动器路线兼容的长期候选

| 内容 | 触发条件 |
|------|----------|
| Stop hook 确定性回写 | best-effort 回调实测不可靠时 |
| MCP 工具面扩展（inbox/knowledge 只读等） | 有真实跨领域需求时 |
| 派发 prompt 模板进化（携带项目规范、相关代码路径提示） | 实测 agent 上下文不足时 |
| 其他 agent 接入（Kimi Code 等） | 核实其 MCP 支持情况后 |

## 8. 风险红线（所有 Phase 继承）

- **不可信内容闸门**：派发 prompt 默认只含用户自己写的 issue 内容；若将来把 Inbox/RSS 内容自动拼入，agent 将带 MCP 写权限读不可信内容（lethal trifecta 半成型），必须显式设计审批档位，不得静默混入（spike §7）
- **DONE/CANCELLED 永远人拖**，任何阶段不开放
- **删除类操作永不进 MCP 工具面**

## 9. 简历衔接（随路线更新）

- Phase 0：无简历价值（便利功能）
- Phase 1：可写——"同一套领域操作，对内 Spring AI Tool Calling（20+ 工具），对外 MCP server 双暴露，coding agent 自主回写任务状态"的生态集成故事；同时勾掉 `docs/career/feature-roadmap.md` #9
- Phase 2（若做）：才是编排深度故事（进程管理 / 协议适配 / 隔离 / 审批门），对应初版讨论的最高简历价值

## 10. 后续动作

Phase 0 开工前：按 §4 写 `.scratch/agent-dispatch/spec.md`（lite-spec）+ 拆 tickets（`.scratch/agent-dispatch/issues/`）。Phase 1 完成时同步勾掉 roadmap #9。
