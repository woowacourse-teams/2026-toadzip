package com.toadzip.backend.ingest.collection.lh.supply.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifiedLhSupplyReplacementRequest(
        @NotBlank @Size(max = 2000) String requestDescription,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String proposedFingerprint,
        @NotBlank @Size(max = 2000) String evidenceUrl,
        @NotBlank @Size(max = 1000) String reason
) {
}
