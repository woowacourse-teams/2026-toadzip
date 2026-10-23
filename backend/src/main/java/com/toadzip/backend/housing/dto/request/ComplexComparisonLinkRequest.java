package com.toadzip.backend.housing.dto.request;

import com.toadzip.backend.housing.domain.ComplexComparisonSourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ComplexComparisonLinkRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotToken,
        @NotNull ComplexComparisonSourceType category,
        @NotBlank @Size(max = 512) String sourceKey,
        @NotBlank @Size(max = 2000) String evidenceNote) { }