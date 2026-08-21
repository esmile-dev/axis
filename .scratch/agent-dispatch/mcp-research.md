---
status: verified-research
feature: agent-dispatch
created: 2026-08-21
---

# Phase 1 调研：Axis 作为 MCP server 对接本地 Claude Code

调研方式说明：本机网络对 `github.com` / `raw.githubusercontent.com` / `code.claude.com` / `docs.claude.com` 均不可达（curl 超时），因此一手来源改为三个更硬的渠道：

1. **Maven Central 实拉** Spring AI 2.0.0 正式构件（starter pom / autoconfigure jar / MCP SDK jar），用 `javap` 读字节码与 `spring-configuration-metadata.json`
2. **本机 spike 实测**：`/tmp/mcp-spike`（Spring Boot 4.0.7 + Spring AI 2.0.0，与 Axis 同版本）起真实 MCP server，curl 完成 initialize / tools/list / tools/call 全链路
3. **Claude Code CLI 实测**：真注册、真连接、真移除（已复原，未留配置残留）

## 结论速览

- **可行，且已实证**。Spring AI 2.0.0 `spring-ai-starter-mcp-server-webmvc` + Spring Boot 4.0.7 构建、启动、MCP 全链路握手通过；Claude Code 2.1.210 无鉴权直连成功（`claude mcp list` health check ✔ Connected）。
- **传输选 Streamable HTTP**（端点 `/mcp`），注册命令 `claude mcp add --transport http --scope user axis http://127.0.0.1:7789/mcp`。SSE 传输 MCP spec 已标记 deprecated，仅作兼容备选。
- **必须显式设置 `spring.ai.mcp.server.protocol: STREAMABLE`**——不设时实测只开旧版 SSE 端点（配置元数据的 "default: streamable" 有误导，见 §2.4）。
- **"只暴露 3 个工具"的做法**：新建一个挂 3 个 `@Tool` 方法的 facade Bean + 一个 `MethodToolCallbackProvider` Bean。MCP server 只收集容器里的 `ToolCallback`/`ToolCallbackProvider` Bean，而 Axis 现有 20+ 内部工具是 `ChatClient.tools(...)` 按次绑定的、不是这类 Bean，天然不泄漏。
- **无鉴权 localhost server Claude Code 直接接受**（实测）；OAuth 是 server 要求时才走的可选路径。
- **一个与 plan.md 不符的事实**：`application.yml` 只有 `server.port: 7789`、没有 `server.address`，全仓库 grep 无 `SERVER_ADDRESS`——应用实际绑 0.0.0.0，plan.md §5 安全节"server 绑 127.0.0.1（既有配置）"不成立，Phase 1 应补上绑定。

---

## 1. 本地基线（已确认的仓库事实）

| 事实 | 来源 |
|------|------|
| Spring Boot **4.0.7**（`spring-boot-starter-parent`） | `backend/pom.xml:9` |
| Spring AI **2.0.0**（`spring-ai-bom` import） | `backend/pom.xml:27,33-38` |
| Java 21 | `backend/pom.xml:26` |
| axis-service 同时有 `spring-boot-starter-web` 和 `spring-boot-starter-webflux` | `backend/axis-service/pom.xml:18-25` |
| 现有 MCP 相关依赖：**无** | 三个 pom 全文 |
| 内部工具 5 个 `@Component`（IssueTool 等，`@Tool` 注解风格），经 `ChatClient.tools(inboxTool, issueTool, ...)` 按次绑定，**不注册为 `ToolCallback` Bean** | `backend/axis-agent/src/main/java/com/esmile/axis/ai/AgentService.java:72,113,148` |
| `server.port: 7789`，无 `server.address`（实际绑 0.0.0.0） | `backend/axis-service/src/main/resources/application.yml:1-2` + 全仓库 grep |
| Facade 可直接复用的 service 方法：`IssueService.findById(String)` / `update(...)` / `addComment(String issueId, String content)` | `backend/axis-service/src/main/java/com/esmile/axis/service/IssueService.java:37,64,99` |

## 2. 问题一：Spring AI 2.0 MCP Server 接入

### 2.1 starter 坐标与依赖链（Maven Central 实拉，非文档转述）

坐标：`org.springframework.ai:spring-ai-starter-mcp-server-webmvc`（版本由 `spring-ai-bom` 管理，无须显式写）。

来源：`https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-starter-mcp-server-webmvc/2.0.0/spring-ai-starter-mcp-server-webmvc-2.0.0.pom`（HTTP 200，本机已下载），其 compile 依赖：

- `org.springframework.boot:spring-boot-starter-web:4.1.0`（构件按 Boot 4.1.0 编译；Axis 用 4.0.7 时由父 BOM 降级管理——spike 已验证兼容，见 §2.5）
- `org.springframework.ai:spring-ai-autoconfigure-mcp-server-webmvc:2.0.0`
- `org.springframework.ai:spring-ai-mcp:2.0.0`
- `org.springframework.ai:spring-ai-mcp-annotations:2.0.0`
- `org.springframework.ai:mcp-spring-webmvc:2.0.0`

MCP Java SDK 版本：`spring-ai-mcp-2.0.0.pom` 声明 `io.modelcontextprotocol.sdk:mcp:2.0.0`（该 pom 再聚合 `mcp-core:2.0.0` + `mcp-json-jackson3:2.0.0`，`mcp` 本体 jar 仅 META-INF）。注意：官方升级文档说 "Spring AI 2.0 requires MCP Java SDK 1.0.0"，但实际发布构件已用到 SDK 2.0.0——以 pom 为准。

官方文档（可达）：[MCP Overview](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) 确认 2.0 起 `mcp-spring-webmvc`/`mcp-spring-webflux` 从 `io.modelcontextprotocol.sdk` 迁入 `org.springframework.ai`，用 BOM 时无须显式版本。

**与 Spring Boot 4 的兼容性**：spike 实测（§2.5）用与 Axis 完全相同的 Boot 4.0.7 + Spring AI 2.0.0 构建并启动成功。axis-service 中 webflux starter 与 web 共存的组合在 spike 中未复现（spike 只有 web），但 Boot 下 web 优先、应用为 servlet 栈，MCP webmvc 自动配置匹配 servlet——风险低，标为未实测项（§5）。

### 2.2 工具暴露机制（autoconfigure jar 字节码 + spike 实测）

两条路，都来自 `spring-ai-autoconfigure-mcp-server-common-2.0.0.jar`：

**路线 A：`ToolCallback`/`ToolCallbackProvider` Bean（推荐，复用现有 `@Tool` 风格）**

`ToolCallbackConverterAutoConfiguration` 的方法签名（javap 实读）：

```
public List<McpServerFeatures$SyncToolSpecification> syncTools(
    ObjectProvider<List<ToolCallback>>, List<ToolCallback>,
    ObjectProvider<List<ToolCallbackProvider>>, ObjectProvider<ToolCallbackProvider>,
    McpServerProperties)
```

即：**容器里所有 `ToolCallback` / `ToolCallbackProvider` Bean 会被全部转成 MCP 工具**。`@Tool` 注解方法经 `MethodToolCallbackProvider.builder().toolObjects(x).build()` 包装即成 `ToolCallbackProvider`（类存在于 `spring-ai-model-2.0.0.jar` 的 `org.springframework.ai.tool.method`，本地 `.m2` 已核实）。

重要推论：**现有 `@Component` 工具类（IssueTool 等）不会被自动扫描暴露**——它们不是 `ToolCallback` Bean，只是被 `ChatClient.tools()` 按次引用（`AgentService.java:72`）。反之，暴露面 = 你显式声明的 `ToolCallback(/Provider)` Bean 的并集，所以要收敛就只声明一个包 3 工具的 provider。**注意**：`syncTools` 也收 `List<ToolCallback>`——今后任何模块若因别的目的注册了 `ToolCallback` Bean，会静默进入 MCP 工具面，实现时需在配置类注释里立此警示。

spike 实证：`@Tool` 方法 + `MethodToolCallbackProvider` Bean → `tools/list` 返回该工具（含自动生成的 JSON Schema），`tools/call` 正常返回（§2.5 输出）。

**路线 B：`@McpTool` 注解扫描**（`spring-ai-mcp-annotations`，starter 自带）

`@McpTool`/`@McpResource`/`@McpPrompt`/`@McpComplete`，自动配置扫描注册，开关 `spring.ai.mcp.server.annotation-scanner.enabled`（默认 true）。来源：[MCP Server Boot Starters 文档](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html) + `McpServerAnnotationScannerProperties` 字节码（`enabled=true`，`CONFIG_PREFIX="spring.ai.mcp.server.annotation-scanner"`）。

路线 A 与 Axis 现有代码风格（`@Tool` + 显式装配）一致，且与内部 Agent 工具共享同一套注解心智，推荐 A；路线 B 无须额外 Bean 但引入第二套注解体系。

### 2.3 传输层与端点（字节码 + 实测）

`McpServerProperties$ServerProtocol` 枚举三值：`SSE` / `STREAMABLE` / `STATELESS`（javap）。webmvc 侧两个自动配置各注册一组 `RouterFunction`：

| 协议 | 自动配置类 | 端点（默认） |
|------|-----------|--------------|
| STREAMABLE | `McpServerStreamableHttpWebMvcAutoConfiguration` | `POST/GET /mcp`（单端点，MCP spec 现行标准） |
| SSE（旧版，spec 已 deprecated） | `McpServerSseWebMvcAutoConfiguration` | `GET /sse`（建流）+ `POST /mcp/message`（发消息） |

端点路径来源：`McpServerStreamableHttpProperties`（`CONFIG_PREFIX="spring.ai.mcp.server.streamable-http"`，`mcpEndpoint="/mcp"`）与 `McpServerSseProperties`（`sseEndpoint="/sse"`，`sseMessageEndpoint="/mcp/message"`），均 javap 实读。

### 2.4 ⚠️ 默认协议陷阱（实测发现，与配置元数据矛盾）

- `McpServerProperties.protocol` 字段默认值是 `STREAMABLE`（javap 构造函数字节码），`spring-configuration-metadata.json` 也写 `spring.ai.mcp.server.protocol default: streamable`。
- **但**两个传输的开关是 `@ConditionalOnProperty`（直接读 Environment，不看字段默认值）：
  - SSE：`havingValue=SSE, matchIfMissing=true`（javap -v 注解实读）
  - STREAMABLE：`havingValue=STREAMABLE, matchIfMissing=false`
- **实测结论（spike）**：不显式设置时只开 `/sse`（200，SSE 流正常），`/mcp` 返回 404；显式 `--spring.ai.mcp.server.protocol=STREAMABLE` 后 `/mcp` 正常、`/sse` 404。

→ **`application.yml` 必须显式写 `spring.ai.mcp.server.protocol: STREAMABLE`**，不能依赖默认值。

### 2.5 spike 实测记录（全部为本机一手输出）

spike 环境：`/tmp/mcp-spike`，pom 用 `spring-boot-starter-parent:4.0.7` + `spring-ai-bom:2.0.0` + `spring-boot-starter-web` + `spring-ai-starter-mcp-server-webmvc`（即 Axis 的目标组合），一个 `@Tool ping(message)` 方法 + `MethodToolCallbackProvider` Bean，端口 18090。`mvn package` 一次通过（同时证明 starter 声明的 web 4.1.0 与父 BOM 4.0.7 无冲突）。

不设 protocol 启动：

```
GET /sse        → 200 text/event-stream，首帧 event:endpoint data:/mcp/message?sessionId=...
POST /mcp       → 404
```

`--spring.ai.mcp.server.protocol=STREAMABLE` 启动，curl 全链路：

```
POST /mcp initialize  → 200，响应头 Mcp-Session-Id: 5af84f22-...，
  body: {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18",
        "capabilities":{...,"tools":{"listChanged":true}},
        "serverInfo":{"name":"axis-probe","version":"0.0.1"}}}
POST /mcp notifications/initialized → 202
POST /mcp tools/list   → SSE 帧 event:message，tools:[{name:"ping",
                          inputSchema:{properties:{message:{...}},required:["message"]}}]
POST /mcp tools/call ping {"message":"hello-axis"}
                       → {"content":[{"type":"text","text":"\"pong: hello-axis\""}],"isError":false}
GET  /sse              → 404
```

观察：① server 与 client 协商 `protocolVersion=2025-06-18`（请求方给什么就谈什么，SDK 支持范围见 §3）；② String 返回值被 JSON 序列化带了一层引号（`"\"pong: hello-axis\""`），cosmetic 级别， facade 返回文本时注意。

### 2.6 关键配置项（`spring-configuration-metadata.json` 全量，括号内为默认值）

```
spring.ai.mcp.server.enabled (true)                # 总开关
spring.ai.mcp.server.protocol (字段默认 streamable) # SSE|STREAMABLE|STATELESS，必须显式设，见 §2.4
spring.ai.mcp.server.name ("mcp-server") / .version ("1.0.0") / .instructions
spring.ai.mcp.server.type (sync)                   # SYNC|ASYNC
spring.ai.mcp.server.stdio (false)
spring.ai.mcp.server.request-timeout (20s)
spring.ai.mcp.server.streamable-http.mcp-endpoint ("/mcp")
spring.ai.mcp.server.streamable-http.disallow-delete (false)
spring.ai.mcp.server.streamable-http.keep-alive-interval (无)
spring.ai.mcp.server.sse-endpoint ("/sse") / .sse-message-endpoint ("/mcp/message") / .base-url ("")
spring.ai.mcp.server.capabilities.{tool,resource,prompt,completion} (均 true)
spring.ai.mcp.server.{tool,resource,prompt}-change-notification (均 true)
spring.ai.mcp.server.expose-mcp-client-tools (false)
spring.ai.mcp.server.annotation-scanner.enabled (true)
```

## 3. 问题二：Claude Code 作为 MCP client（本机 CLI 2.1.210 实测）

`claude --version` → `2.1.210 (Claude Code)`。官方文档站本机不可达，以下全部来自 `claude mcp --help` / `claude mcp add --help` 原文与本机注册实测。

### 3.1 注册命令语法

`claude mcp add [options] <name> <commandOrUrl> [args...]`，关键选项（add --help 原文）：

- `-t, --transport <transport>`：**stdio / sse / http**，默认 stdio（URL 形式的 server 必须显式给 http 或 sse）
- `-s, --scope <scope>`：**local / user / project**，默认 local
- `-H, --header <header...>`：自定义 header，可多次。help 示例即 bearer token：`--header "Authorization: Bearer ..."`（鉴权问题 → 是，能带）
- `-e, --env <env...>`：stdio server 的环境变量
- OAuth 相关：`--client-id` / `--client-secret`（或 `MCP_CLIENT_SECRET` env）/ `--callback-port`（预注册 redirect URI 场景）

HTTP server 注册（help 原文示例）：`claude mcp add --transport http sentry https://mcp.sentry.dev/mcp`

### 3.2 scope 语义与持久化位置（实测确认）

| scope | 持久化位置 | 来源 |
|-------|-----------|------|
| `local`（默认） | `~/.claude.json` 的项目节，仅本人在本项目可见 | 实测 add 输出：`File modified: /Users/alan/.claude.json [project: /Users/alan/coding/projects/axis]`；`mcp get` 显示 `Scope: Local config (private to you in this project)` |
| `user` | `~/.claude.json` 顶层，所有项目可用 | 实测 add 输出：`Added ... to user config / File modified: /Users/alan/.claude.json` |
| `project` | 项目根 `.mcp.json`，随仓库共享，需审批才连接 | `mcp list` 帮助原文：`Unapproved .mcp.json servers are shown as ⏸ Pending approval`；`reset-project-choices` 帮助原文：`Reset all approved and rejected project-scoped (.mcp.json) servers within this project` |

对 Axis：`--scope user` 一次注册全仓库可用、不污染任何目标仓库——与 plan.md §5 的设想一致，实测语法成立。

### 3.3 鉴权：OAuth 非强制，无鉴权 localhost 直连成功（端到端实证）

对 spike server（STREAMABLE，无任何鉴权、无 header）实测：

```
$ claude mcp add --transport http axis-probe http://127.0.0.1:18090/mcp
Added HTTP MCP server axis-probe with URL: http://127.0.0.1:18090/mcp to local config
$ claude mcp list
axis-probe: http://127.0.0.1:18090/mcp (HTTP) - ✔ Connected
```

`claude mcp list` 的 health check 本身就是完整 MCP 握手——这一行同时证明了：**无 OAuth 要求** + **协议兼容** + **传输匹配**。另有存量佐证：本机已有的 `idea: http://127.0.0.1:64342/sse (SSE) - ✔ Connected`，同样无鉴权。

OAuth 是**可选的、server 驱动**的路径：`claude mcp login <name>`（帮助原文：`Authenticate with an MCP server (HTTP, SSE, or claude.ai connector)`）+ add 时的 `--client-id/--client-secret/--callback-port`。Axis Phase 1 单用户本地场景按 plan.md 决策不做 auth，实测成立；将来要加 simplest 的防护可用 `-H "Authorization: Bearer ..."` + 服务端一个校验 filter。

（两个 probe server 已 `claude mcp remove` 清理干净，`~/.claude.json` 无残留。）

## 4. 问题三：协议兼容性

**MCP Java SDK 2.0.0 支持的协议版本**（`mcp-core-2.0.0.jar` 的 `io.modelcontextprotocol.spec.ProtocolVersions`，javap -constants 实读）：

```
2024-11-05 / 2025-03-26 / 2025-06-18 / 2025-11-25
```

**两代传输的取舍**（[MCP spec 2025-06-18 transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)，一手 spec）：

- 现行标准传输只有两种：**stdio** 与 **Streamable HTTP**（单端点 POST+GET，可选 SSE 流式回包）。
- 旧 HTTP+SSE 传输（2024-11-05 版协议）已 **deprecated**，spec 仅以后向兼容章节描述（client 应先 POST initialize，4xx 再回退 GET SSE）。
- Claude Code 对两者都支持（`--transport http|sse`，CLI 一手事实）。

→ **选 STREAMABLE**：现行标准、单端点、与 Claude Code `--transport http` 对齐；SSE 只作为"万一某 client 不支持"的备选（protocol 改 `SSE` 即可双端点切换，成本一行配置）。

**版本协商**：spec 规定 client 后续请求带 `MCP-Protocol-Version` 头、缺省按 `2025-03-26` 处理；SDK 与 Claude Code 的协商实测成功（§3.3 的 Connected），具体谈到哪个版本未抓包（server 日志 warn 级未记录），不影响结论。

**spec 安全建议**（同页 Security Warning，均为 SHOULD 级）：server 校验 `Origin` 头防 DNS rebinding；本地 server 只绑 127.0.0.1；应有鉴权。Axis 单用户本地场景：绑定 127.0.0.1 是必做项（当前未做，见结论速览最后一条）；Origin 校验 Spring AI 传输层是否内建未核实（§5）。

## 5. Phase 1 接入方案草图（基于上述事实）

### 5.1 依赖与配置

依赖加在 **axis-agent** 的 pom（MCP 是 agent 侧协议适配层，与 `ai/` 工具同模块；axis-service 业务模块零改动。自动配置由 `AxisApplication` 扫描 `com.esmile.axis` 全局生效，放哪个模块技术上都行，放 axis-agent 语义最贴）：

```xml
<!-- backend/axis-agent/pom.xml -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

```yaml
# backend/axis-service/src/main/resources/application.yml
spring:
  ai:
    mcp:
      server:
        name: axis
        version: 0.1.0
        protocol: STREAMABLE   # 必须显式，不设实测只开旧版 /sse（§2.4）
```

```yaml
# 同文件 server 节，补上绑定（plan.md 声称的"既有配置"实际不存在）
server:
  address: 127.0.0.1
```

### 5.2 工具面：3 工具 facade（"部分暴露"的最自然做法）

新文件 `backend/axis-agent/src/main/java/com/esmile/axis/ai/mcp/AxisMcpTools.java`（新包 `ai/mcp/`，与内部 `ai/tool/` 并列）：

```java
@Component
@RequiredArgsConstructor
public class AxisMcpTools {

    private static final Set<String> ALLOWED = Set.of("BACKLOG", "TODO", "IN_PROGRESS");

    private final IssueService issueService;

    @Tool(description = "读取 Issue 详情（标题、描述、状态、优先级、评论）")
    public String get_issue(@ToolParam(description = "Issue 的 ID") String id) { ... }

    @Tool(description = "给 Issue 添加评论（完成汇报：改动摘要、跑过的测试、遗留问题）")
    public String add_issue_comment(@ToolParam(description = "Issue 的 ID") String id,
                                    @ToolParam(description = "评论内容") String content) { ... }

    @Tool(description = "流转 Issue 状态（仅允许 BACKLOG/TODO/IN_PROGRESS；DONE/CANCELLED 由人在 UI 操作）")
    public String transition_issue_status(@ToolParam(description = "Issue 的 ID") String id,
                                          @ToolParam(description = "目标状态：BACKLOG/TODO/IN_PROGRESS") String status) {
        // 白名单校验，拒绝 DONE/CANCELLED
    }
}
```

加配置类（同包）声明**唯一**的 provider Bean：

```java
@Configuration
class McpServerConfig {
    /** MCP 工具面 = 本 Bean 所包对象的全部 @Tool 方法。
     *  注意：MCP server 会收集容器内所有 ToolCallback/ToolCallbackProvider Bean——
     *  新增这类 Bean 即等于扩大 MCP 暴露面，勿在本类之外注册。 */
    @Bean
    ToolCallbackProvider axisMcpToolCallbacks(AxisMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
```

要点（均有上文事实支撑）：

- 内部 20+ 工具不会泄漏——它们经 `ChatClient.tools()` 按次绑定，不是 `ToolCallback` Bean（`AgentService.java:72`），MCP converter 只认容器 Bean（§2.2）。
- 不接 `ConfirmationService`——3 个工具全非删除类，且 confirmation gate 依赖 `/chat` SSE 流挂起线程，MCP 通道无此机制；防护靠 plan.md 的双保险（工具面收敛 + Claude Code 终端逐次审批）。
- `addComment`/`findById`/`update` 现成（`IssueService.java:37,64,99`），facade 是薄组装，业务规则（状态白名单）放 facade 内校验——符合仓库分层约定（业务校验在 service 层之上的边界处 fail-fast）。

### 5.3 Claude Code 注册与验收

```bash
claude mcp add --transport http --scope user axis http://127.0.0.1:7789/mcp
claude mcp list        # 期望：axis: http://127.0.0.1:7789/mcp (HTTP) - ✔ Connected
```

对应 plan.md §5 验收 1；验收 2/3（回写闭环、DONE 拒绝）在终端会话里让 agent 实际调用即可。补充两个 curl 级自检（不依赖 LLM）：`POST /mcp initialize` 应 200 且 `serverInfo.name=axis`；`tools/list` 应恰好 3 个工具。

### 5.4 工作量重估

spike 已趟平依赖/配置/协议三件事，剩余实现 = 1 个 facade（3 方法，复用现成 service）+ 1 个配置类 + yml 3 行 + 派发 prompt 模板改文案。plan.md 估的 2~3 天偏保守，核心编码约半天，大头在 prompt 模板调优与端到端验收。

## 6. 仍不确定的点

1. **默认协议行为与元数据矛盾**：字节码（SSE `matchIfMissing=true`）+ spike 实测（不设只开 `/sse`）一致，但与 `spring-configuration-metadata.json` 的 `default: streamable` 表述冲突。已用显式配置规避，不影响方案；上游可能是 bug 或意图不明。
2. **Origin 校验 / DNS rebinding 防护**：spec 为 SHOULD 级建议，Spring AI 的 `WebMvcStreamableServerTransportProvider` 是否内建 Origin 校验未核实（需读源码或实测带恶意 Origin 头的请求）。缓解措施已就位（绑 127.0.0.1 + Claude Code 非浏览器不自动带 Origin）。
3. **Claude Code 文档侧交叉验证缺失**：`code.claude.com`/`docs.claude.com`/`docs.anthropic.com` 本机不可达，§3 全部依赖 CLI `--help` + 本机实测。scope/审批/OAuth 语义以 CLI 为准，未与官方文档文字交叉确认。
4. **协议版本协商细节未抓包**：Claude Code 与 server 实际协商到哪个 `protocolVersion` 未记录（连接成功即证明兼容，但不知道谈的是 2025-06-18 还是 2025-11-25）。
5. **webflux + web 双 starter 共存未实测**：spike 只含 web；Axis axis-service 同时有 webflux starter。Boot 下应用为 servlet 栈、MCP webmvc 自动配置匹配 servlet，理论无冲突，但集成后第一次启动需看 `/mcp` 是否如期注册。
6. **String 返回值的多余 JSON 引号**：`tools/call` 结果文本为 `"\"pong: hello-axis\""`（@Tool String 返回经 JSON 序列化）。对 agent 理解无实质影响；若介意可让 facade 返回 record 或在 `tool-response-mime-type` 上想办法，未深究。

## 附：本调研产生的一次性环境（可复现）

- `/tmp/mcp-spike/`：spike 工程（pom + 单文件 Application + yml），`mvn package && java -jar target/mcp-spike-0.0.1.jar --spring.ai.mcp.server.protocol=STREAMABLE` 可复跑 §2.5。
- `/tmp/mcp-research/`：从 Maven Central 拉的 2.0.0 构件与解包结果（javap 实据）。
- spike 进程已 kill、`claude mcp` 配置已复原。
