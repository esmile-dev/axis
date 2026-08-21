package com.esmile.axis.dispatch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * .command 脚本生成：cd 路径 + quoted heredoc 原样包裹 prompt（引号/美元符/多行不展开），
 * 作为 claude 启动参数传入（自动提交，派发弹窗的 prompt 审查即确认闸门）。
 * Warp tab config：terminal pane 的 commands 只跑 bash <脚本>，TOML 内无 prompt、无新增转义面。
 */
class TerminalLauncherTest {

    @Test
    void buildScriptEmbedsRepoPathAndPromptVerbatim() {
        String prompt = "标题含 \"引号\" 和 $HOME\n第二行保持原样";
        String script = TerminalLauncher.buildScript("/tmp/my repo", prompt);

        assertThat(script).startsWith("#!/bin/bash\n");
        assertThat(script).contains("cd \"/tmp/my repo\" || exit 1");
        assertThat(script).contains(prompt);
        // heredoc 分隔符带随机后缀且引号包裹（防 shell 展开 $HOME 等）
        assertThat(script).containsPattern("<<'AXIS_PROMPT_[0-9a-f]{32}'");
    }

    @Test
    void buildWarpTabConfigRunsBashScriptInTerminalPane() {
        String toml = TerminalLauncher.buildWarpTabConfig("axis-dispatch-a1b2c3d4", "/tmp/my repo", "/tmp/axis-dispatch-x.command");

        assertThat(toml).contains("name = \"axis-dispatch-a1b2c3d4\"");
        // 必须 terminal pane——"agent" 会打开 Warp 自己的 Agent Mode 而非 claude CLI
        assertThat(toml).contains("type = \"terminal\"");
        assertThat(toml).contains("directory = \"/tmp/my repo\"");
        assertThat(toml).contains("commands = [\"bash \\\"/tmp/axis-dispatch-x.command\\\"\"]");
    }
}
