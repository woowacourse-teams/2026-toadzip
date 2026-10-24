package com.toadzip.backend.ingest.enrichment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class LhHouseholdComplexComparison {
    private LhHouseholdComplexComparison() {
    }

    /**
     * 세대수 보강 실패 원천과, 공급유형·전체 세대수·지역이 같은 단지 후보를 현재 데이터로 비교한 결과다.
     * linkable은 후보가 정확히 하나라 관리자가 확인 후 연결을 저장할 수 있다는 뜻이다.
     */
    public record View(long failureId, String token, Source source, List<Candidate> candidates, SavedLink savedLink,
            boolean linkable) {
    }

    /** invalidReason이 있으면 원천을 보강 입력으로 바꾸지 못해 후보를 찾지 않았다. */
    public record Source(String areaName, String supplyTypeName, String complexName, Integer totalHouseholdCount,
            List<HousingTypeCount> housingTypes, String invalidReason) {
    }

    public record HousingTypeCount(BigDecimal exclusiveArea, int householdCount) {
    }

    public record Candidate(long id, String name, String supplyType, int totalHouseholdCount, String roadAddress,
            List<BigDecimal> exclusiveAreas) {
    }

    public record SavedLink(Long complexId, String complexName, String sourceUrl, String reason, String actor,
            Instant updatedAt, boolean reviewRequired) {
    }

    public record SaveRequest(@NotNull @Positive Long complexId, @NotBlank @Size(max = 64) String token,
            @Size(max = 2048) String sourceUrl, @NotBlank @Size(max = 2000) String reason) {
    }

    public record ReleaseRequest(@NotBlank @Size(max = 64) String token,
            @NotBlank @Size(max = 2000) String reason) {
    }
}
