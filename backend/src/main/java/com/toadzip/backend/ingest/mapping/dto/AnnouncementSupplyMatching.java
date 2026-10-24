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
            Integer totalSupplyHouseholdCount, SplitGroup splitGroup, boolean excluded) {
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

    public record ComplexOption(long id, String name, String roadAddress, String supplyType, String pnu,
            int totalHouseholdCount, List<HousingTypeArea> housingTypes) {
    }

    public record HousingTypeArea(long id, String name, BigDecimal exclusiveArea) {
    }

    public record HousingTypeOption(long id, String name, BigDecimal exclusiveArea, BigDecimal supplyArea,
            Integer householdCount, String token) {
    }

    /**
     * 보낸 원천행만 선택대로 정제하고 나머지 행은 자동 정제한다. splits는 같은 트랜잭션에서 원천행을
     * 여러 공급행으로 나눈다. 저장 후 공고의 모든 원천행이 매칭되어야 한다.
     */
    public record RefineRequest(@NotNull @Size(max = 1000) List<@NotNull @Valid Request> rows,
            Boolean remember, @Size(max = 2048) String sourceUrl, @Size(max = 2000) String reason,
            @Size(max = 50) List<@NotNull @Valid SplitPlanRequest> splits) {
        public boolean rememberSelection() {
            return Boolean.TRUE.equals(remember);
        }

        public List<SplitPlanRequest> splitPlans() {
            if (splits == null) {
                return List.of();
            }
            return splits;
        }
    }

    public record SplitPlanRequest(@NotBlank @Size(max = 500) String rowIdentifier,
            @NotBlank @Size(max = 64) String token,
            @NotNull @Size(min = 2, max = 50) List<@NotNull @Valid SplitSelection> rows) { }

    public record ReleaseRequest(@NotBlank @Size(max = 500) String rowIdentifier,
            @NotBlank @Size(max = 64) String token, @NotBlank @Size(max = 2000) String reason) {
    }

    public record ExclusionRequest(@NotBlank @Size(max = 500) String rowIdentifier,
            @NotBlank @Size(max = 64) String token, boolean excluded, @NotBlank @Size(max = 2000) String reason) {
    }
}
