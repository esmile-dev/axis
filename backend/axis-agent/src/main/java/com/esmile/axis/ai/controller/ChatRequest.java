package com.esmile.axis.ai.controller;

import jakarta.validation.constraints.NotBlank;

/**
 * /api/agent/chat 与 /chat/sync 的请求体
 */
public record ChatRequest(
        @NotBlank String message,
        String sessionId) {

    private static final String DEFAULT_SESSION = "default-session";

    public String sessionIdOrDefault() {
        return sessionId == null || sessionId.isBlank() ? DEFAULT_SESSION : sessionId;
    }
}
