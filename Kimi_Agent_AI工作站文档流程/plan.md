# Plan：个人项目 AI 功能开发文档流设计

## 目标
为 Vue + Spring Boot 个人项目设计一套「完整但不过重」的 AI 开发文档流：
1. 文档清单 + 各自职责  2. 流转顺序与确认门禁  3. 防过重机制与豁免规则  4. 每份文档的模板骨架
产出：对话内方案说明（含理由）+ 可直接放进 repo 的模板文件包（zip）。

## Stage 0 — 启动（主代理）
- 写 plan.md、建 todos
- 派后台 explore 子代理做行业调研（Stage R），与 Stage 1 并行

## Stage R — 行业现状调研（explore 子代理，后台，只读）
- 核实 GitHub spec-kit / AWS Kiro 截至 2026 年中的工作流、文件结构、门禁机制
- 补充 OpenSpec / BMAD-METHOD / cc-sdd 等面向个人的 SDD 实践及"过重"争议
- 产出：共性文档集、门禁模式、单人项目裁剪共识（附来源）
- 无匹配技能，Orchestrator 自定义指导

## Stage 1 — 方案与模板起草（主代理，不依赖 Stage R）
- 设计：全局 2 份（workflow 宪章、constitution）+ 功能级 3 份（需求/设计/任务）+ 条件触发的变更记录
- 写 /mnt/agents/output/ai-doc-flow/ 下全部模板、CLAUDE 片段、填好的示例

## Stage 2 — 校验整合（主代理）
- 用 Stage R 结论交叉校验方案，补充行业依据、修正术语
- 打包 zip

## Stage 3 — 交付
- 对话内按用户 4 个问题组织答复（含理由），引用模板文件包
