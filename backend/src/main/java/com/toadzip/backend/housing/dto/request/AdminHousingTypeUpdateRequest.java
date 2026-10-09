package com.toadzip.backend.housing.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record AdminHousingTypeUpdateRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 255) String name,
        @NotNull @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal exclusiveArea,
        @PositiveOrZero Integer householdCount) { }
