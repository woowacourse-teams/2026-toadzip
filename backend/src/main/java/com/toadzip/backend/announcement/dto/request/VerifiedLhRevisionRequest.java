package com.toadzip.backend.announcement.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifiedLhRevisionRequest(
        @Positive long previousAnnouncementId,
        @NotBlank @Pattern(regexp = "[0-9]{16}") String previousPanId,
        @NotBlank @Pattern(regexp = "[0-9]{16}") String correctedPanId,
        @NotBlank @Size(max = 2000) @Pattern(regexp = "^https?://[^\\s]+$") String evidenceUrl,
        @NotBlank @Size(max = 255) String reason
) {
}
