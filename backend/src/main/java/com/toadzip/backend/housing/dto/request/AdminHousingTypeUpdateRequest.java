package com.toadzip.backend.housing.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.Valid;
import com.toadzip.backend.admin.dto.AdminDocumentEvidence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record AdminHousingTypeUpdateRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 255) String name,
        @NotNull @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal exclusiveArea,
        @PositiveOrZero Integer householdCount,
        @Valid AdminDocumentEvidence documentEvidence,
        @Size(max = 64) String impactToken) {
    public AdminHousingTypeUpdateRequest(Long version, String name, BigDecimal exclusiveArea, Integer householdCount,
            AdminDocumentEvidence documentEvidence) {
        this(version, name, exclusiveArea, householdCount, documentEvidence, null);
    }
}
