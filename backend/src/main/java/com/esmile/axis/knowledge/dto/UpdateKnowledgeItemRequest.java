package com.esmile.axis.knowledge.dto;

import com.esmile.axis.knowledge.KnowledgeStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UpdateKnowledgeItemRequest(
        @Size(max = 500) String title,
        KnowledgeStatus status,
        @Min(0) @Max(100) Integer progress,
        Set<String> tags
) {
}
