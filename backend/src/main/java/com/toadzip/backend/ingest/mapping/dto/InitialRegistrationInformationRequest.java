package com.toadzip.backend.ingest.mapping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record InitialRegistrationInformationRequest(
        @NotBlank @Size(max = 255) String sourceComplexIdentifier,
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String fingerprint,
        @Size(max = 255) String complexName,
        @NotNull @Size(max = 200) Map<@NotBlank @Size(max = 1000) String,
                @NotBlank @Size(max = 255) String> housingTypeNames,
        @NotBlank @Size(max = 2000) String evidence) {
}
