package com.esmile.axis.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record FetchKnowledgeRequest(
        @NotBlank @Pattern(regexp = "(?i)^https?://.+", message = "url 必须是 http/https 链接") String url
) {
}
