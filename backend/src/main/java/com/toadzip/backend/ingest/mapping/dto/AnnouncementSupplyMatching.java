package com.toadzip.backend.ingest.mapping.dto;

import java.math.BigDecimal;
import java.util.List;

public final class AnnouncementSupplyMatching {
    private AnnouncementSupplyMatching() {
    }

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
