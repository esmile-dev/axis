---
status: verified         # draft → approved → verified
feature: agent-dispatch
created: 2026-08-21
---

# 精简规格：Phase 1 MCP server + 回写闭环（S 级）

上游计划：`.scratch/agent-dispatch/plan.md` §5。一手事实依据：`.scratch/agent-dispatch/mcp-research.md`（同版本 spike 已端到端实证：Boot 4.0.7 + Spring AI 2.0.0 握手通过、Claude Code 2.1.210 无鉴权直连 Connected）。

## 0. 背景知识：MCP 是怎么工作的

**MCP（Model Context Protocol）** 是 Anthropic 2024 年底发布的开放协议，标准化"LLM 应用 ↔ 外部能力"的连接方式——类比 AI 应用的 USB-C：能力提供方实现一次 server，所有支持 MCP 的 agent 都能接入。

**三个角色**：Host（带 LLM 的 agent 应用，如 Claude Code）内嵌 Client（协议客户端，每个 server 一条连接），连向 Server（暴露能力的进程/服务）。本 feature 中 Claude Code 是 Host+Client，Axis 后端是 Server。

**消息与传输**：协议消息是 JSON-RPC 2.0（`{"jsonrpc":"2.0","id":1,"method":"tools/call",...}`）。传输二选一：stdio（server 是 client 拉起的子进程，走管道）或 Streamable HTTP（单端点 `POST /mcp`，回包可选 SSE 流式）。Axis 本就是 HTTP 服务，选后者；旧版 HTTP+SSE 双端点传输已被 spec 标记 deprecated，仅作兼容备选。

**一次调用的完整链路**（以本 feature 为例）：

1. 握手：client `initialize` → server 回 `protocolVersion`（版本协商）+ `capabilities`（声明有 tools 能力）+ `serverInfo`（`name=axis`）；client 回 `notifications/initialized` 完成握手
2. 发现：client `tools/list` → server 返回工具清单（name + description + inputSchema JSON Schema），Claude Code 将其注入 LLM 上下文
3. 决策：LLM 读到派发 prompt 里的"完成后调用 add_issue_comment"，输出结构化 tool_use 块
4. 执行：client 把 tool_use 翻译成 `tools/call` 请求 → server 执行对应 Java 方法 → 文本结果回传
5. client 把结果喂回 LLM，agent 继续后续生成

**与 Axis 现有 Tool Calling 的关系**：`/api/agent/chat` 是"对内"——Axis 自己是 Host，20+ 工具经 `ChatClient.tools()` 进程内直接调用。Phase 1 是"对外"——同一套 `@Tool` 注解风格，但工具由容器内 `ToolCallback` Bean 承载，被 MCP server 自动收集后经网络暴露给外部 agent。方向相反的对称结构：对内 Axis 调 LLM，对外 LLM（在 Claude Code 里）调 Axis。

**LLM 为什么会"乖乖"调工具**：没有魔法——`tools/list` 的 description 与派发 prompt 的指令共同构成上下文，模型按指令选工具。回写闭环的可靠性 = 工具 description 质量 + prompt 指令清晰度，本质仍是 best-effort（终态 DONE 永远人拖，与可靠性解耦）。

## 1. 需求

背景：Phase 0 把任务"派出去"（终端开 Claude），但干完 Axis 里无记录。Phase 1 把"收回来"：Axis 暴露收敛的 MCP 工具面，派发 prompt 带上回写指令，agent 干完自己写完成汇报评论——闭环成型，终态 DONE 仍永远人拖。

| 编号 | FR | 验收标准（当…时，系统应…） |
|------|-----|---------------------------|
| FR-101 | MCP server 接入：axis-agent pom 加 `spring-ai-starter-mcp-server-webmvc`（BOM 管版本）；`application.yml` 显式 `spring.ai.mcp.server.protocol: STREAMABLE` + `name: axis` | curl `POST /mcp` initialize → 200 且 `serverInfo.name=axis`；不显式设 protocol 时只开 `/sse` 的陷阱不复现（research §2.4） |
| FR-102 | 工具面恰好 3 个：`get_issue` / `add_issue_comment` / `transition_issue_status`（新包 `ai/mcp/` 的 facade `AxisMcpTools` + 全应用唯一 `MethodToolCallbackProvider` Bean） | curl `tools/list` 返回且仅返回 3 个工具（内部 20+ Agent 工具零泄漏）；单测三方法行为 |
| FR-103 | 状态白名单：`transition_issue_status` 仅接受 `BACKLOG`/`TODO`/`IN_PROGRESS`；`DONE`/`CANCELLED`/非法值返回可读拒绝文本、不执行变更 | 单测：白名单内外各路径；E2E：终端会话中 agent 试转 DONE 被拒 |
| FR-104 | `application.yml` 补 `server.address: 127.0.0.1`（当前实际绑 0.0.0.0，MCP 写工具对局域网暴露，research 发现） | 启动后 `lsof` 确认绑 127.0.0.1:7789；既有前端（localhost 访问）无回归 |
| FR-105 | 派发默认 prompt 升级：`标题 + 描述 + 回写指令段`（含 issue id、`add_issue_comment` 用法与汇报内容要求、`get_issue` 可用提示）；弹窗可编辑覆盖不变 | E2E：派发后 agent 汇报评论实际出现在 Issue 下（含改动摘要） |
| FR-106 | 注册与闭环：`claude mcp add --transport http --scope user axis http://127.0.0.1:7789/mcp` | `claude mcp list` 显示 ✔ Connected；完整链路走通（派发→干活→回写→人审拖 DONE） |

**范围外**：MCP auth（绑 127.0.0.1 + user scope 后，单用户本地风险可接受；将来加固走 `-H bearer` + 服务端 filter）、Origin 校验（Spring AI 是否内建未证实，research §6；绑定已缓解 DNS rebinding）、stdio 传输、MCP resources/prompts 能力、`ConfirmationService` 接入（MCP 通道无挂起-恢复机制，且工具面全非删除类）、其他 agent（Kimi Code 等）的 MCP 支持核实。

## 2. 方案要点（均已在 research 中立据）

- **依赖加 axis-agent pom**（MCP 是 agent 侧协议适配层，与 `ai/` 同模块；axis-service 业务模块零改动——`IssueService.findById/update/addComment` 现成复用）。`AxisApplication` 全局扫描 `com.esmile.axis`，自动配置跨模块生效。
- **⚠️ 必须显式 `protocol: STREAMABLE`**：SSE 开关是 `matchIfMissing=true`、STREAMABLE 是 `matchIfMissing=false`，不设时只开旧版 `/sse`（配置元数据的 "default: streamable" 有误导）。端点单 `/mcp`。
- **"只暴露 3 个"的原理**：MCP server 只收集容器内 `ToolCallback`/`ToolCallbackProvider` Bean；内部 20+ 工具是 `ChatClient.tools()` 按次绑定（`AgentService.java:72`），天然不进 MCP 面。配置类注释立警示：**今后任何模块注册 `ToolCallback(/Provider)` Bean 都会静默进入 MCP 工具面**。
- **facade 薄组装**：`AxisMcpTools` 3 个 `@Tool` 方法，状态白名单等业务校验在 facade 内 fail-fast；不接 `ConfirmationService`（无非删除类操作可确认）。
- **已知 cosmetic**：`@Tool` String 返回值经 JSON 序列化会多一层引号（research §2.5），对 agent 理解无实质影响，不处理。
- **回写指令段**（默认 prompt 模板追加，实现期调优）：
  ```
  ---
  任务来自 Axis 任务系统（issue id: <id>）。完成后请用 axis MCP 工具回写：调用
  add_issue_comment 提交完成汇报（改动摘要、跑过的测试、遗留问题）；需要核对
  需求细节可用 get_issue。
  ```
- **best-effort 语义不变**：agent 不回调不留脏状态（系统从不自动改 Issue 状态）；DONE/CANCELLED 永远人在 UI 拖。

## 3. 任务

| ✓ | 任务 | 验收点 | Commit |
|---|------|--------|--------|
| ☐ | axis-agent pom + `application.yml`（protocol STREAMABLE + name + server.address） | FR-101/104（curl initialize + lsof） | |
| ☐ | `ai/mcp/AxisMcpTools` + `McpServerConfig`（唯一 provider Bean + 警示注释）+ `AxisMcpToolsTest` | FR-102/103（单测 + curl tools/list 恰好 3） | |
| ☐ | `DispatchService.defaultPrompt` 追加回写指令段 + 单测适配 | FR-105（构建级） | |
| ☐ | E2E：`claude mcp add --scope user` 注册 → Connected → 真实派发 → 回写评论出现 → DONE 拒绝 | FR-102/103/105/106 | |
| ☐ | 文档：plan.md Phase 1 勾掉、roadmap #9 勾掉、AGENTS.md 补 MCP 条目、career 条目（若写） | 文档一致 | |

## 4. 验收记录

2026-08-21 全部通过（E2E 实例：`SERVER_PORT=7790` 起新 jar，验毕已停；测试数据与注册项已清理）：

- FR-101：curl `POST /mcp` initialize → 200，`serverInfo.name=axis`、`version=0.1.0`、协商 `protocolVersion=2025-06-18`，capabilities 含 tools ✓
- FR-102：`tools/list` 恰好返回 `get_issue`/`add_issue_comment`/`transition_issue_status` 3 个（内部 20+ Agent 工具零泄漏）；`AxisMcpToolsTest` 8 例绿 ✓
- FR-103：curl `tools/call transition_issue_status(DONE)` → 返回拒绝文本、DB 状态不变（随后 `IN_PROGRESS` 正常放行入库）；白名单外/非法值单测覆盖 ✓
- FR-104：`lsof` 确认绑 `127.0.0.1:7790 (LISTEN)`，非 0.0.0.0 ✓
- FR-105：真实派发（Warp）→ Claude 进程启动参数含完整 prompt（标题+描述+回写指令段）→ agent 建好 `hello.txt` 后经 MCP 调 `add_issue_comment` 回写完成汇报（改动摘要/测试/遗留问题三段齐全）→ `get_issue` 回读评论在 ✓
- FR-106：`claude mcp add --transport http --scope user`（E2E 用临时名 axis-e2e 指 7790，验毕已 remove）→ `claude mcp list` ✔ Connected ✓
- 构建：`mvn clean package` 全绿（含新增 `AxisMcpToolsTest` 8 例与 `DispatchServiceTest` prompt 断言适配）
- **E2E 顺带发现并修复的既有 bug**：`GET/POST /api/issues/{id}/comments` 在有评论时 500（`Comment.issue` 懒加载代理在事务外序列化，`open-in-view: false`）。修为 `Comment.issue` 改 `@JsonIgnore`（前端只经 `GET /{id}` 内嵌读评论，该路径本就不序列化 `issue`，JSON 形状无变化）；修后 GET/POST 均 200，`GET /{id}` 内嵌 comments 回归无损。
