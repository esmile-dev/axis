-- Issue 派发给本地 coding agent 时的工作目录（agent-dispatch Phase 0），可空：未配置的项目不显示派发入口
ALTER TABLE project ADD COLUMN repo_path text;
