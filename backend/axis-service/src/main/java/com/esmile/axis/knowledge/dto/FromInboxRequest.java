package com.esmile.axis.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

public record FromInboxRequest(
        @NotBlank String inboxItemId
) {
}
