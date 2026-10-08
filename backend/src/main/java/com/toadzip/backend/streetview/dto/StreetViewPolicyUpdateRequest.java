package com.toadzip.backend.streetview.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record StreetViewPolicyUpdateRequest(
        @NotNull @PositiveOrZero @Schema(description = "현재 조회한 정책 version") Long version,
        @NotNull Boolean enabled,
        @NotBlank @Size(max = 500) @Schema(description = "관리자에게만 공개되는 변경 사유") String reason
) {
    public StreetViewPolicyUpdateRequest {
        if (reason != null) {
            reason = reason.trim();
        }
    }
}
