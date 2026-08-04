package com.esmile.axis.knowledge.controller;

import jakarta.validation.constraints.NotBlank;

/**
 * /api/knowledge/{id}/chat 的请求体
 */
public record KnowledgeChatRequest(@NotBlank String message) {
}
