package com.esmile.axis.ai.controller;

import jakarta.validation.constraints.NotBlank;

/**
 * /api/agent/chat 与 /chat/sync 的请求体
 */
public record ChatRequest(
        @NotBlank String message,
        String sessionId,
        Boolean retry) {

    private static final String DEFAULT_SESSION = "default-session";

    public String sessionIdOrDefault() {
        return sessionId == null || sessionId.isBlank() ? DEFAULT_SESSION : sessionId;
    }

    /** 手动重试（失败消息的重发）：后端先去重已落库的同一 user 消息 */
    public boolean retryOrDefault() {
        return Boolean.TRUE.equals(retry);
    }
}
