package com.toadzip.backend.ingest.mapping.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ComplexMergePreviewRequest(
        @NotNull @Size(min = 2, max = 20) List<@NotNull @Positive Long> complexIds,
        @NotNull @Positive Long lhSourceId
) {
}
