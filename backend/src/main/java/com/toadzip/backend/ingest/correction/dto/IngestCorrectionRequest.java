package com.toadzip.backend.ingest.correction.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record IngestCorrectionRequest(
        @NotBlank @Size(max = 100) String token,
        @NotNull @Size(min = 1, max = 500) List<@Valid Row> rows,
        @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude
) {
    public record Row(@NotBlank String sourceKey, @NotNull Map<String, Object> changes) {
    }
}
