package com.esmile.axis.dispatch;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/**
 * 在本机终端打开交互式 Claude Code 并注入任务 prompt。
 * 共用部分：生成 .command 脚本（cd 到 repoPath + quoted heredoc 传 prompt 防转义，末尾 exec bash 留在仓库目录）。
 * 按终端分流：TERMINAL 经 `open -a Terminal` 直接执行脚本；WARP 无 AppleScript/CLI 接口，
 * 走 Tab Config（~/.warp/tab_configs/ 下生成 toml，terminal pane 里 exec bash 同一脚本）
 * + `open warp://tab_config/<name>`——已有 Warp 窗口时在其中开新 tab，无窗口才新开窗口。
 */
@Component
public class TerminalLauncher {

    private static final Path WARP_APP = Path.of("/Applications/Warp.app");
    private static final long LAUNCH_CONFIG_TTL_MS = 3_600_000;

    public void launch(String repoPath, String prompt, DispatchTerminal terminal) {
        try {
            Path script = Files.createTempFile("axis-dispatch-", ".command");
            Files.writeString(script, buildScript(repoPath, prompt));
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
            switch (terminal) {
                case TERMINAL -> new ProcessBuilder("open", "-a", "Terminal", script.toString()).start();
                case WARP -> launchWarp(repoPath, script);
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to launch terminal: " + e.getMessage(), e);
        }
    }

    private void launchWarp(String repoPath, Path script) throws IOException {
        if (!Files.isDirectory(WARP_APP)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Warp is not installed: " + WARP_APP);
        }
        Path configDir = Path.of(System.getProperty("user.home"), ".warp", "tab_configs");
        Files.createDirectories(configDir);
        // 自清：删掉 1 小时前生成的 axis-dispatch-*.toml，避免在 Warp 新标签页菜单里堆积
        long cutoff = System.currentTimeMillis() - LAUNCH_CONFIG_TTL_MS;
        try (var stream = Files.list(configDir)) {
            stream.filter(p -> p.getFileName().toString().matches("axis-dispatch-.*\\.toml"))
                    .filter(p -> p.toFile().lastModified() < cutoff)
                    .forEach(p -> p.toFile().delete());
        }
        String name = "axis-dispatch-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Files.writeString(configDir.resolve(name + ".toml"), buildWarpTabConfig(name, repoPath, script.toString()));
        // warp:// URI 只投递事件、不带前台激活语义（tab 开了但焦点还留在浏览器）——先 activate 再发 URI
        new ProcessBuilder("open", "-a", "Warp").start();
        new ProcessBuilder("open", "warp://tab_config/" + name).start();
    }

    static String buildScript(String repoPath, String prompt) {
        // heredoc 分隔符带随机后缀，避免 prompt 内容恰好等于分隔符导致提前终结；引号包裹防 shell 展开
        String delimiter = "AXIS_PROMPT_" + UUID.randomUUID().toString().replace("-", "");
        return """
                #!/bin/bash
                cd "%s" || exit 1
                claude "$(cat <<'%s'
                %s
                %s
                )"
                exec bash
                """.formatted(repoPath, delimiter, prompt, delimiter);
    }

    static String buildWarpTabConfig(String name, String repoPath, String scriptPath) {
        // type 必须 "terminal"（标准 shell 跑脚本起 claude CLI）；"agent" 会打开 Warp 自己的 Agent Mode。
        // commands 只跑 bash <脚本>——claude 命令与 prompt 全部留在 .command 里，TOML 无新增转义面
        return """
                name = "%s"
                [[panes]]
                id = "main"
                type = "terminal"
                directory = "%s"
                commands = ["bash \\"%s\\""]
                """.formatted(name, repoPath, scriptPath);
    }
}
