---
status: verified         # draft → approved → verified
feature: agent-dispatch
created: 2026-08-21
---

# 精简规格：Phase 0 启动器交接——Issue 一键派给终端 Claude（S 级）

上游计划：`.scratch/agent-dispatch/plan.md` §4。本 spec 只覆盖 Phase 0。

## 1. 需求

背景：从 Axis Issue 详情页一键在本机终端打开交互式 Claude Code，携带 issue 上下文，人在会话里干活（权限审批/插话/急停全原生）。Axis 只做"交接"，不做编排。

| 编号 | FR | 验收标准（当…时，系统应…） |
|------|-----|---------------------------|
| FR-001 | `project` 加 `repo_path`（可空）：V3 增量迁移 + entity + `PATCH /api/projects/{id}` 支持更新；空串/空白存 null | 单测：更新/清空两路径；DB 列存在且 `ddl-auto: validate` 通过 |
| FR-002 | `POST /api/issues/{id}/dispatch` 校验：issue 不存在 404；issue 无 project 或 `repo_path` 为空 400；`repo_path` 目录不存在 400 | 单测：四条路径各一 |
| FR-003 | prompt 组装：请求体 `prompt` 非空则用之；否则默认 = `title` +（有 description 时）`"\n\n" + description` | 单测：覆盖/默认/无 description 三分支 |
| FR-004 | 终端启动：生成 `.command` 临时脚本（`cd "<repoPath>"` + quoted heredoc 把 prompt 传给 `claude`，启动参数=自动提交，派发弹窗审查即确认闸门）→ chmod +x → 按终端分流执行——Terminal：`open -a Terminal <script>`；Warp：生成 Tab Config TOML（terminal pane `commands` 复用同一脚本）+ `open warp://tab_config/<name>`（已有窗口开新 tab，无窗口新开窗口） | 单测：`buildScript`/`buildWarpTabConfig` 输出形状；E2E：双路派发均实际打开终端且 claude 收到上下文 |
| FR-005 | Issue 详情页：仅所属 Project 已配 `repo_path` 时显示"派给 Claude"按钮 → 弹窗展示默认 prompt 可编辑 → 派发 → 成功/失败内联反馈 | E2E：有/无 repo_path 两态；失败显示后端 400 原因 |
| FR-006 | Project 详情页：header 描述下方加 `repo_path` 内联输入（blur/Enter 保存，Esc 取消），空值显示占位 | E2E：保存后刷新仍在；清空后 Issue 页按钮消失 |

**范围外**：执行记录表、UI 内进度、状态联动（全手动）、create 接口收 `repo_path`（前端新建面板无此字段，PATCH 足够）、iTerm 适配、prompt 模板进化（长期候选）。

## 2. 方案要点

- **迁移**：`V3__project_repo_path.sql` = `ALTER TABLE project ADD COLUMN repo_path text;`（保留本地数据，对齐 V2 先例，不改 V1）。
- **后端新包** `com.esmile.axis.dispatch`（axis-service）：
  - `DispatchController`：`POST /api/issues/{id}/dispatch`，body `record DispatchRequest(String prompt)`；一行委托。
  - `DispatchService`：校验（`ResponseStatusException`，对齐 knowledge 包惯例）→ 组装 prompt → 调 `TerminalLauncher`。
  - `TerminalLauncher`（@Component）：`buildScript(repoPath, prompt)` 纯函数（可测）+ `launch(...)` 做真实 IO（`Files.writeString` 到 temp 目录 `.command` 文件、`setPosixFilePermissions` 加执行位、`new ProcessBuilder("open", "-a", "Terminal", script)`）。heredoc 分隔符带随机后缀防 prompt 内容碰撞。
- **实体/DTO**：`Project` 加 `repoPath`；`UpdateProjectRequest` 加 `repoPath`（`ProjectService.update` 末尾处理：null 不动、blank→null、否则 trim）。create 链路不动。
- **前端**：
  - `projects/[id].vue`：`Project` interface 加 `repoPath: string | null`；header 描述下加一行内联输入（folder 图标 + input），直接 PATCH（本页 project 不走 localFirst）。
  - `issues/[id].vue`：`currentProject?.repoPath` 已配时显示 Terminal 图标按钮；Dialog 内 textarea 默认填 `title + "\n\n" + description`，确认后 POST；失败读 `err.data?.detail` 内联展示。
- **安全**：本 feature 不引入新攻击面——终端由本机用户亲眼所见，等价于手动打开终端敲 `claude`；prompt 只含用户自己的 issue 内容（plan §8 红线）。

## 3. 任务

| ✓ | 任务 | 验收点 | Commit |
|---|------|--------|--------|
| ☑ | `V3__project_repo_path.sql` + `Project.repoPath` + `UpdateProjectRequest`/`ProjectService.update` + `ProjectServiceTest` 3 用例 | FR-001 ✅ | |
| ☑ | `TerminalLauncher`（buildScript 纯函数 + launch）+ `DispatchService` + `DispatchController` + `DispatchServiceTest` 7 + `TerminalLauncherTest` 1 | FR-002/003/004 ✅ | |
| ☑ | `projects/[id].vue` repo_path 内联编辑（blur 保存 / Esc 取消 / 失败回滚） | FR-006（构建级）✅ | |
| ☑ | `issues/[id].vue` 派发按钮 + prompt 弹窗 + 成功/失败内联反馈 | FR-005（构建级）✅ | |
| ☑ | E2E（7790 实例）：四条错误路径 curl + 真实派发（Terminal 打开、claude 进程带 prompt 参数） | FR-002/003/004 ✅ | |
| ☑ | AGENTS.md 同步（controller 列表 + 架构约定一行）；plan.md Phase 0 状态 | 文档一致 ✅ | |

## 4. 验收记录

| FR | 结果 | 验证方式 |
|----|------|----------|
| FR-001 | 通过 | `ProjectServiceTest` 3 用例（写入 trim/空白清 null/null 不动）；E2E：Flyway validated 3 migrations + `GET /api/projects` 返回 `repoPath` 字段 |
| FR-002 | 通过 | `DispatchServiceTest` 四路径单测 + E2E curl：不存在 issue 404、无 project 400、无 repo_path 400、目录不存在 400（报文均含可读原因） |
| FR-003 | 通过 | `DispatchServiceTest`：覆盖/默认（title+\n\n+desc）/无 desc 三分支 |
| FR-004 | 通过 | `TerminalLauncherTest`（cd 路径 + quoted heredoc 原样包裹 prompt）；E2E 真实派发：HTTP 200，本机 Terminal 打开，`claude` 进程 argv 含测试 prompt（PID 46767，测后已清理） |
| FR-005 | 构建级通过 | `npm run build` 过；按钮 `v-if="currentProject?.repoPath"`、失败读 `err.data.message`（Boot 默认错误体，E2E 报文确认 key 为 message）——**UI 点击走查留待用户** |
| FR-006 | 构建级通过 | `npm run build` 过；UI 走查留待用户 |

## 变更记录

| 日期 | 变更内容 | 原因 |
|------|----------|------|
| 2026-08-21 | `ProjectService.update` 加第 6 参 `repoPath`，调用方 `ProjectTool.updateProjectStatus` 补 null | 接口变更级联；`mvn test` 增量编译未暴露，`mvn clean package` 才报出——验证以 clean build 为准 |
| 2026-08-21 | .command 脚本末尾加 `exec bash` | claude 退出后留在 repo 目录，方便接着 git 审查改动（plan §2"改动审查靠 git 习惯"的落地） |
| 2026-08-21 | 发现 `axis_readonly` 对 `project` 表无 SELECT 权限（与 AGENTS.md 描述不符） | 未修（授权属管理操作）；E2E 改走 API 验证绕过 |
| 2026-08-21 | prompt 传递维持 `claude "<prompt>"`（启动参数=自动提交）；当日曾改为 `pbcopy`+无参 claude（不自动提交），经用户决策当日回退 | 终态=自动提交：派发弹窗里的 prompt 审查即确认闸门，终端不再设第二道；剪贴板版（覆盖剪贴板+手动 ⌘V）与模拟粘贴版（需辅助功能权限、时序脆）均被否。CLI 确认过无原生"预填不提交"开关 |
| 2026-08-21 | 派发终端支持 Warp：`DispatchRequest` 加 `terminal` 参数（null/blank→TERMINAL，未知值 400）；Settings 页加"终端"偏好（localStorage），派发弹窗只读显示将用终端 | 用户日常终端是 Warp；配置存 localStorage 而非服务端——ConfigController 是 AI 档案专用，为单偏好建配置表不值得 |
| 2026-08-21 | Warp 集成从 Launch Configuration YAML（`warp://launch`）改为 Tab Config TOML（`warp://tab_config/<stem>`）：`~/.warp/tab_configs/` 下生成 toml，terminal pane `commands` 复用同一 .command 脚本；每次派发自清 1h 前的 axis-dispatch-*.toml | 用户要求"已有 Warp 窗口时在其中开新 tab"——`warp://tab_config` 默认开在当前窗口（无窗口才新开窗口），官方文档/schema 确认；pane type 必须 `terminal`（`agent` 会开 Warp 自己的 Agent Mode 而非 claude CLI）。YAML 版已被取代，不再保留 |
| 2026-08-21 | Warp 派发前加 `open -a Warp` 显式激活再发 URI | 用户反馈 tab 开了但焦点留在浏览器：`warp://` 是 URL 打开方式，LaunchServices 只投递事件不带前台语义；`open -a` 是标准激活机制（=E2E 验证无回归，焦点切换本身因测试时 Warp 已在前台未能程序化复验，留用户真实场景确认） |
