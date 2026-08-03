package com.esmile.axis.knowledge.dto;

import com.esmile.axis.knowledge.KnowledgeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateKnowledgeItemRequest(
        KnowledgeType type,
        @NotBlank @Size(max = 500) String title,
        @NotBlank String content,
        @Size(max = 2048) String sourceUrl,
        Set<String> tags
) {
}
