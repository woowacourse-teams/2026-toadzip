package com.toadzip.backend.ingest.mapping.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AnnouncementSupplyMatching {
    private AnnouncementSupplyMatching() {
    }

    public record Request(@NotBlank @Size(max = 500) String rowIdentifier,
            @NotBlank @Size(max = 64) String token, @NotNull @Positive Long complexId,
            @Positive Long housingTypeId, @Size(max = 64) String targetToken) {
        public Request(String rowIdentifier, String token, Long complexId, Long housingTypeId) {
            this(rowIdentifier, token, complexId, housingTypeId, null);
        }
    }

    public record Row(String rowIdentifier, String token, String sourceComplexName, String sourceHousingTypeName,
            String pnu, BigDecimal exclusiveArea, BigDecimal supplyArea,
            Long complexId, Long housingTypeId, String failure, SavedMatch savedMatch,
            Integer totalSupplyHouseholdCount, SplitGroup splitGroup) {
    }

    public record SavedMatch(Long complexId, Long housingTypeId, String sourceUrl, String reason,
            String actor, Instant updatedAt, boolean reviewRequired) { }

    public record SplitGroup(long id, Integer sharedHouseholdCount, String sourceUrl, String reason,
            String actor, Instant updatedAt, boolean reviewRequired, List<SplitRow> rows) { }

    public record SplitRow(long id, String name, Long complexId, Long housingTypeId) { }

    public record SplitRequest(@NotBlank @Size(max = 500) String rowIdentifier,
            @NotBlank @Size(max = 64) String token,
            @NotBlank @Size(max = 2048) String sourceUrl, @NotBlank @Size(max = 2000) String reason,
            @NotNull @Size(min = 2, max = 50) List<@NotNull @Valid SplitSelection> rows) { }

    public record SplitSelection(@Positive Long id, @NotBlank @Size(max = 200) String name,
            @NotNull @Positive Long complexId, @NotNull @Positive Long housingTypeId,
            @NotBlank @Size(max = 64) String targetToken) { }

    public record ComplexOption(long id, String name, String roadAddress, String supplyType) {
    }

    public record HousingTypeOption(long id, String name, BigDecimal exclusiveArea, BigDecimal supplyArea,
            Integer householdCount, String token) {
    }

    public record RefineRequest(@NotNull @Size(min = 1, max = 1000) List<@NotNull @Valid Request> rows,
            Boolean remember, @Size(max = 2048) String sourceUrl, @Size(max = 2000) String reason) {
        public boolean rememberSelection() {
            return Boolean.TRUE.equals(remember);
        }
    }

    public record ReleaseRequest(@NotBlank @Size(max = 500) String rowIdentifier,
            @NotBlank @Size(max = 64) String token, @NotBlank @Size(max = 2000) String reason) {
    }
}
