package com.esmile.axis.llm;

/**
 * AI 配置已重载（档案激活/更新/删除或手动 reload）。监听器（如 PgVectorStore
 * 装配）应据此重建依赖 embedding/chat 配置的组件。
 */
public record AiConfigReloadedEvent() {
}
