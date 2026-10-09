package com.toadzip.backend.ingest.mapping.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class AnnouncementSupplyMatching {
    private AnnouncementSupplyMatching() {
    }

    public record Request(@NotBlank @Size(max = 500) String rowIdentifier,
            @NotBlank @Size(max = 64) String token, @NotNull @Positive Long complexId,
            @Positive Long housingTypeId) {
    }

    public record Row(String rowIdentifier, String token, String sourceComplexName, String sourceHousingTypeName,
            String pnu, BigDecimal exclusiveArea, BigDecimal supplyArea,
            Long complexId, Long housingTypeId, String failure) {
    }

    public record ComplexOption(long id, String name, String roadAddress, String supplyType) {
    }

    public record HousingTypeOption(long id, String name, BigDecimal exclusiveArea, BigDecimal supplyArea) {
    }

    public record RefineRequest(@NotNull @Size(min = 1, max = 1000) List<@NotNull @Valid Request> rows) {
    }
}
