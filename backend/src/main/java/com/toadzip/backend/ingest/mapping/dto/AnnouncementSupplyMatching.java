package com.toadzip.backend.ingest.mapping.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AnnouncementSupplyMatching {
    private AnnouncementSupplyMatching() {
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

    public record ComplexOption(long id, String name, String roadAddress, String supplyType, String pnu,
            Integer totalHouseholdCount, List<HousingTypeArea> housingTypes) {
    }

    public record HousingTypeArea(long id, String name, BigDecimal exclusiveArea) {
    }

    /**
     * basicDeposit·basicMonthlyRent는 PDF 보정 검토가 공고 값과 나란히 보여 줄 주택형의 지금 기본 임대조건이다.
     * rentalConditionSource가 ANNOUNCEMENT이면 이전 PDF 보정에서 관리자가 고른 값이라 검토 화면이 바꾸기 전에 확인을 받는다.
     */
    public record HousingTypeOption(long id, String name, BigDecimal exclusiveArea, BigDecimal supplyArea,
            Integer householdCount, String token, Long basicDeposit, Long basicMonthlyRent,
            String rentalConditionSource) {
    }
}
