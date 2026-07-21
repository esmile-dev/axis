# AI 功能开发文档流 · 模板包

适用于个人项目（前后端分离）+ Claude Code 等 AI 编程工具的 Spec-Driven 开发。

## 结构
```
docs/workflow.md        流程宪章：分级、门禁、豁免、规模上限（写一次）
docs/constitution.md    项目宪章：技术栈基线、约定、禁止事项、AI 规则（写一次）
docs/templates/         5 个模板：00 精简版(M级) / 01 需求 / 02 设计 / 03 任务 / prompt 文件模板
example/avatar-upload/  一个填好的 L 级完整示例
CLAUDE-snippet.md       并入 CLAUDE.md 的片段
```

## 上手 3 步
1. 把 `docs/` 复制到你的仓库根目录，按实际修改 `constitution.md`
2. 把 `CLAUDE-snippet.md` 内容并入根目录 `CLAUDE.md`
3. 下个功能开始时，让 AI 按 workflow.md 分级并起草文档

## 核心机制一句话
文档头部 front-matter 的 `status` 字段就是门禁：AI 起草（draft）→ 你确认（approved）→ AI 才进入下一阶段；编号链 FR → 设计 implements → 任务 → AC 保证可追溯。

## AI/Agent 功能支持（个人 AI 工作站适用）
- workflow.md 第 7 节：AI 功能特别条款（评测驱动验收、prompt 视同契约、危险动作需确认）
- 01/02/03 模板含 AI 专用条件小节（非 AI 功能直接删除该节即可）
- `prompts/` 存 prompt 文件（用 prompt 模板，带版本号）；`evals/` 存评测集
