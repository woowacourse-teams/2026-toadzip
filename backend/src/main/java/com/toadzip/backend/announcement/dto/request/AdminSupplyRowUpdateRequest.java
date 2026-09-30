package com.toadzip.backend.announcement.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AdminSupplyRowUpdateRequest(@NotNull @PositiveOrZero Long version,
        @Positive Long housingComplexId, @Positive Long housingTypeId,
        @NotNull @Valid SupplyData supplyRow) {
    public record SupplyData(@NotBlank @Size(max = 255) String sourceComplexName,
            @NotBlank @Size(max = 255) String sourceHousingTypeName,
            @NotBlank @Size(max = 255) String supplyPnu,
            java.time.YearMonth expectedMoveInMonth,
            @NotNull com.toadzip.backend.announcement.domain.SupplyCategory supplyCategory,
            @PositiveOrZero Integer totalSupplyHouseholdCount) { }
}
