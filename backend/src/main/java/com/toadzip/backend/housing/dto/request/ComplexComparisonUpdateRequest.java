package com.toadzip.backend.housing.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ComplexComparisonUpdateRequest(
        @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String snapshotToken,
        @NotNull @Valid AdminHousingComplexUpdateRequest current,
        @NotBlank @Size(max = 2000) String evidenceNote,
        Boolean addressConfirmed) { }

