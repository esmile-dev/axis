package com.esmile.axis.knowledge.controller;

import jakarta.validation.constraints.NotBlank;

/**
 * /api/knowledge/ask 的请求体
 */
public record KnowledgeAskRequest(@NotBlank String question) {
}
