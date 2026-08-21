package com.esmile.axis.ai.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP server 装配（agent-dispatch Phase 1）。
 * ⚠️ MCP server 会收集容器内所有 ToolCallback/ToolCallbackProvider Bean——
 * 今后任何模块注册这类 Bean 都会静默扩大 MCP 暴露面（/mcp 无鉴权），勿在本类之外注册。
 * 内部 Agent 工具走 ChatClient.tools() 按次绑定（AgentService），天然不进 MCP 面。
 */
@Configuration
class McpServerConfig {

    @Bean
    ToolCallbackProvider axisMcpToolCallbacks(AxisMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
