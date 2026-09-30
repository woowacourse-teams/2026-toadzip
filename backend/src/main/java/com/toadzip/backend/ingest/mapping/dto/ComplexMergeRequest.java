package com.toadzip.backend.ingest.mapping.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ComplexMergeRequest(
        @NotNull UUID operationId,
        @NotNull @Size(min = 2, max = 20) List<@NotNull @Positive Long> complexIds,
        @NotNull @Positive Long lhSourceId,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedHash,
        @AssertTrue boolean confirmedSameComplex,
        @NotBlank @Size(min = 10, max = 2000) String reason
) {
}
