package com.toadzip.backend.ingest.enrichment.dto;

import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.HousingTypeCount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 같은 단지에 매칭된 LH 임대 목록 묶음들과 그 단지의 주택형을 현재 데이터로 나란히 보여 준다.
 * decidable은 세대수가 서로 다른 묶음이 둘 이상이라 관리자가 결정을 저장할 수 있다는 뜻이다.
 */
public record LhHouseholdTargetDecisionView(long failureId, String token, Complex complex, List<SourceGroup> groups,
        boolean identical, boolean decidable, SavedDecision decision) {

    public record Complex(long id, String name, String supplyType, int totalHouseholdCount, String roadAddress,
            List<ComplexHousingType> housingTypes) {
    }

    public record ComplexHousingType(long id, String name, BigDecimal exclusiveArea, Integer totalHouseholdCount) {
    }

    /** failureSource는 지금 보고 있는 오류의 묶음이다. */
    public record SourceGroup(String sourceKey, String complexName, String supplyTypeName, String areaName,
            int totalHouseholdCount, List<HousingTypeCount> housingTypes, boolean failureSource) {
    }

    public record SavedDecision(String decision, String chosenSourceKey, String reason, String actor,
            Instant updatedAt, boolean reviewRequired) {
    }

    public record SaveRequest(@NotNull @Pattern(regexp = "USE_SOURCE|SKIP") String decision,
            @Size(max = 500) String chosenSourceKey, @NotBlank @Size(max = 64) String token,
            @NotBlank @Size(max = 2000) String reason) {
    }

    public record ReleaseRequest(@NotBlank @Size(max = 64) String token, @NotBlank @Size(max = 2000) String reason) {
    }
}
