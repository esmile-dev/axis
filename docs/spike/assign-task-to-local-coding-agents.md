---
name: assign-task-to-local-coding-agents
description: 在 Axis UI 指定本地 coding agent（Claude Code / Kimi Code / pi 等），把任务 assign 给它执行并回收反馈。本 spike 收敛可行性与集成架构选型。
status: draft
---

# Spike: 从 UI 派任务给本地 Coding Agents

## 1. 背景

用户在 Axis（如 Issue 详情页）选择一个本地已安装的 coding agent CLI，把具体任务 assign 给它；agent 在本地开始工作，进度流式可见，完成后把结果反馈回 UI。本 spike 回答两个问题：**这些 CLI 能否被程序驱动**、**编排层该怎么架构**。

本机实测已安装：Claude Code 2.1.210、Kimi Code 0.33.0、pi 0.82.0。调研日期 2026-08-06。

## 2. 结论速览

**明确可行，且是成熟赛道**（Vibe Kanban / Conductor / Crystal 等产品已验证）。主流 coding agent CLI 全部提供 headless 模式 + 结构化输出（JSONL 事件流）+ 会话恢复，业界标准玩法是：

> 后端 spawn CLI 子进程（pipe，非 PTY）→ 解析 stdout 的 JSON 事件流 → 规范化为统一会话模型 → SSE/WS 推给前端；每任务一个 git worktree 隔离；session_id 落库支撑"继续追问/打回重做"。

三个本机 agent 中，**Kimi Code 集成面最宽**（CLI headless + ACP 协议 + 本地 HTTP server 三形态齐全），Claude Code 事件协议最成熟，pi 的 RPC 协议对多轮驱动最友好。

## 3. 本机三 Agent 能力矩阵

| 能力 | Claude Code 2.1.210 | Kimi Code 0.33.0 | pi 0.82.0 |
|---|---|---|---|
| Headless 单次执行 | `claude -p "…"` | `kimi -p "…"` | `pi -p "…"` |
| 结构化事件流 | `--output-format stream-json`（NDJSON，38 种事件，含 token 级增量） | `--output-format stream-json`（JSONL：assistant/tool_calls/tool 消息） | `--mode json`（session header + turn/message/tool_execution 事件流） |
| 最终结构化结果 | `--output-format json`：`result`/`session_id`/`total_cost_usd`，支持 `--json-schema` | 最后一条 assistant 消息 | `message_end` 事件（权威消息） |
| 会话恢复 | `--resume <id>` / `--continue` / `--fork-session`；存 `~/.claude/projects/<cwd>/<uuid>.jsonl` | `--session <id>` / `--continue`；存 `~/.kimi-code/sessions/<workDirKey>/<id>/` | `--session <id>` / `-c` / `--fork`；`--session-id <自定义id>` **可指定确定性 ID**（如 `issue-<uuid>`）；存 `~/.pi/agent/sessions/<cwd>/<uuid>.jsonl` |
| 无人值守批准 | `--dangerously-skip-permissions` 或 `--permission-mode acceptEdits --allowedTools "Bash(mvn *)"` 细粒度白名单 | `-p` 模式默认 auto 权限、不询问（deny 规则仍生效） | **默认即无人值守**（无内置审批概念），`--tools` 静态裁剪工具面 |
| 长驻协议模式 | `-p --input-format stream-json` stdin 持续喂消息（双向）；SDK 控制协议可注入审批回调 | `kimi acp`：标准 ACP（Agent Client Protocol）JSON-RPC over stdio，`session/new`/`prompt`/`cancel`/权限回调，稳定面覆盖 10/12 | `pi --mode rpc`：JSONL 命令/事件协议，`prompt`/`steer`/`abort`/`switch_session`/`get_session_stats`，**`agent_settled` 事件做完成判定** |
| 本地 HTTP server | ❌（`claude mcp serve` 是 MCP 方向，非派任务） | ✅ `kimi web`：REST + WebSocket，`/openapi.json` + `/asyncapi.json`，bearer token 鉴权，绑 127.0.0.1 | ⚠️ `pi-server` 实验性（CBOR over unix socket），不建议依赖 |
| 编程 SDK | TS `@anthropic-ai/claude-agent-sdk` / Python（**无 Java**）；`canUseTool` 审批回调、`interrupt()`、hooks | 无独立 SDK（ACP 客户端库可复用 `@agentclientprotocol/sdk`） | TS 三层包：`pi-coding-agent`（`createAgentSession`）/`pi-agent-core`/`pi-ai`，官方建议 Node 集成优先用 SDK |
| 后台派发 | `claude --bg "task"` + `claude agents --json` | — | — |
| 资源控制 | `--max-turns` / `--max-budget-usd` / `--model` | `-m` 选模型 | `--model`；`get_session_stats` 拿 token/成本 |
| 来源 | code.claude.com/docs、本地 --help、agent-sdk sdk.d.ts | kimi.com/code/docs（kimi-command / kimi-acp / sessions 页） | pi.dev/docs、pi-mono 仓库 rpc.md/sdk.md/sessions.md、本地 --help 与 dist 代码 |

**关键差异提示**：

- **完成判定都不能只看退出码**。Claude：等 stream-json 的 `result` 行；pi RPC：等 `agent_settled`（`agent_end` 后还可能有 auto-retry/compaction）；pi `--mode json` 下 LLM 错误甚至不进退出码，必须解析 `stopReason`（源码证实）。
- **stdout 必须持续 drain**（claude 有背压等待、pi json 模式同理），stderr 单独消费，否则 agent 卡死。
- **会话按 cwd 归属**：子进程 cwd 决定 session 目录；跨项目引用 session id 会触发 pi 的交互确认而挂起。
- pi 的 print 模式会把管道 stdin 并入 prompt；spawn 时 stdin 接法要明确。
- 三家的会话都是磁盘 JSONL 文件，Axis 可以直接读文件做历史回放，格式均公开。

## 4. 其他 Agent 生态速览（备选/未来扩展）

| Agent | Headless | 结构化输出 | Resume | Server/SDK | 适配度 |
|---|---|---|---|---|---|
| OpenAI Codex CLI | `codex exec` | `--json` JSONL 事件 + `--output-schema` | `codex exec resume <id>` | TS/Python SDK + `app-server` JSON-RPC | **高** |
| **OpenCode** | `opencode run` | `--format json` 事件 | `-c` / `-s <id>` | **`opencode serve` 本地 HTTP API（OpenAPI + SSE + 权限回调端点）+ 官方 JS SDK** | **高** |
| Cursor CLI | `cursor-agent -p` | `stream-json` | `--resume <chat-id>` | 无 | 中 |
| Gemini CLI | `gemini -p` | `json` / `stream-json` | `-r "latest"`（按序号，弱） | 无 | 中 |
| Amp | `amp -x` | `--stream-json` | `amp threads continue [id]` | TS/Python SDK（云绑定） | 中 |
| Aider | `aider -m "…"` | 无（纯文本） | 无 | 无 | 低 |

要点：OpenCode 是唯一把「常驻本地服务 + REST + SSE + 权限回调」做成一等架构的；Codex 的 spawn 派事件模型文档化最好；Aider 不适合做反馈闭环。Axis 后端若走 HTTP 集成路线，OpenCode 和 `kimi web` 是最省事的目标。

## 5. 集成架构选项（编排层怎么接）

四条路线，按 Axis（Spring Boot 后端）适配度排序：

**路线 A：spawn headless CLI + 解析 JSON 事件流（推荐起步）**
Java `ProcessBuilder` spawn `<cli> -p "<prompt>" --output-format stream-json`，逐行解析 NDJSON → 规范化 → SSE 推前端（与现有 `/api/agent/chat` SSE 模式同构）。三家 + Codex/Gemini/Cursor 全都支持，一个 Executor 接口 + 每 agent 一个适配器即可覆盖。缺点：无 Java SDK，审批回调等高级控制面做不到。

**路线 B：长驻协议进程（多轮/中止/审批需求出现时升级）**
`pi --mode rpc` / `kimi acp` / `claude -p --input-format stream-json` / `codex app-server`：进程常驻，stdin 发命令、stdout 收事件，支持 steer/abort/多轮。仍是 spawn，但生命周期从"一次任务"变"一个会话"。

**路线 C：本地 HTTP server（后端零 spawn）**
`kimi web`（REST+WS，OpenAPI 文档）或 `opencode serve`（REST+SSE+权限回调端点）：agent 进程由 CLI 自己管，Axis 只发 HTTP。最省事，但依赖 CLI 的 server 模式稳定性（kimi web 较新，opencode serve 较成熟）。

**路线 D：Node sidecar + 官方 SDK（控制面最全，复杂度最高）**
起一个 Node 进程用 `@anthropic-ai/claude-agent-sdk` / pi 的 `createAgentSession`，拿到 `canUseTool` 审批回调、`interrupt()` 等完整控制面，再对 Java 暴露 HTTP。只有需要「UI 弹窗逐工具审批」时才值得。

**建议：MVP 走路线 A（只支持 claude/kimi/pi 三家 headless），多轮追问与远程审批出现时按 agent 逐个升级 B；pi 因 `--session-id` 确定性 ID + RPC 协议完整，可作为 B 路线首个验证对象。**

## 6. 编排层设计要点（抄 Vibe Kanban 源码级经验）

Vibe Kanban（27.7k stars，Rust+React，支持 10 个 CLI）源码确认的核心设计：

1. **Executor 抽象 + 双层日志模型**：trait 定义 `spawn` / `spawn_follow_up(session_id)` / `normalize_logs`；原始 stdout 持久化一份，同时规范化为统一条目（assistant 消息 / 工具调用含 diff / 命令执行）推给 UI。这是 UI 不重写就能接新 agent 的根本原因。
2. **每执行一个"环形缓冲 + 广播 + 持久化"的 MsgStore**：客户端连上 = 历史回放 + live 续播；页面刷新/断线不丢日志，进程生命周期与前端连接解耦。Axis 可用 SSE + Last-Event-ID 等价实现。
3. **Worktree-per-Task**：`git worktree add` 在仓库外受管目录建 `<issue-id>-<slug>/`；每执行前后记录 HEAD commit——diff 预览、"agent 改没改东西"、回滚全部免费得到。完成后自动 commit，映射 Axis Issue 状态机：agent 干完 → `IN_REVIEW`，人审 diff 后 → `DONE`。
4. **双通道完成判定**：OS 退出码 + 协议层"本轮结束"信号并行监听（有的 agent 干完不退出）；退出后补杀进程组清残留孙进程；**server 重启时孤儿清扫**（DB 里 Running 但进程已死的标 Failed）。
5. **审批即服务**：把"是否允许此工具调用"建模为异步请求-等待（带超时取消），桥接 UI 弹窗；三档策略 Auto / Supervised（只读自动批，写与 shell 人审）/ Plan。

## 7. 安全风险（本场景最大的坑）

从 Web UI 触发本地 agent = 浏览器输入变成宿主机 shell 命令。叠加 prompt injection 构成 Simon Willison 的 **lethal trifecta**（私有数据 + 不可信内容 + 外发通道）。已有真实事故：Gemini CLI "TrustIssues" CVSS 10——公开 issue 藏注入指令，yolo 模式的 bot 读到后窃取 CI secrets。

**对 Axis 特别相关：Inbox / Daily Digest 的 RSS 内容就是不可信内容来源**。若任务 prompt 混入外部内容且 agent 全自动跑，三要素齐聚。

纵深缓解（按优先级）：
1. 危险动作默认需人确认（workflow.md 既有条款）：prompt 混入外部内容的执行默认 Supervised 档起步；yolo 类 flag（`--dangerously-skip-permissions` / `--yolo`）仅输入全可信时可用
2. Worktree 隔离 + cwd 限定；后端绑 127.0.0.1 + Origin 白名单
3. 用 `--allowedTools` / `--tools` 白名单收敛工具面（如禁网络、限定 `Bash(mvn *)`）
4. agent 环境不注入真实高权限凭证；API key 用限定用途的
5. 进阶可选 Anthropic 开源 sandbox-runtime（macOS seatbelt + 网络域名白名单代理）

## 8. 转正路径

按 **L 级** 走完整 feature 流程（新模块 + 新表 + 安全模型）。建议分期：

1. **M1（验证链路）**：后端 `AgentExecutor` 接口 + Claude/Kimi/pi 三个 headless 适配器（路线 A）；Issue 详情页"Assign to agent"按钮；执行记录表（session_id、状态机、原始日志落盘）；SSE 流式进度。**不做 worktree、不做审批回调**，限定在 axis 仓库自身目录 + Supervised 白名单工具。
2. **M2（隔离与闭环）**：worktree-per-issue + before/after commit 记账 + diff 预览 + 完成后自动进 IN_REVIEW；MsgStore 回放（断线重连）；超时/中止（SIGTERM 进程组）。
3. **M3（多轮与审批）**：session resume 实现"打回重做/继续追问"；按需对 pi（RPC）或 claude（SDK sidecar）接入审批回调；成本统计落库。

## 9. 探索性结论

- **可行性**：明确可行。三家本机 agent 的 headless + 结构化输出 + 会话恢复能力全部经官方文档与本地 `--help` 核实；业界有 Vibe Kanban 等开源参照可直接抄架构。
- **首选形态**：路线 A（spawn + stream-json 解析），三家一个抽象全覆盖；Kimi Code 因 `kimi web` 本地 HTTP server 的存在，中长期可演进为路线 C 最省事的目标。
- **不建议先做的事**：PTY 包裹 TUI（Crystal 路线，输出不可结构化）；`pi-server`（实验性）；自研沙箱（先用 worktree + 工具白名单）；支持超过 3 家 agent（M1 阶段 claude/kimi/pi 足够）。
- **不建议绕过的事**：不可信内容（RSS/Inbox）混入任务 prompt 时的审批闸门——这是 CVSS 10 事故的直接教训，也是 workflow.md 既有条款的自然延伸；session_id 与执行记录从第一天落库（多轮追问、成本统计、孤儿清扫全部依赖它）。
