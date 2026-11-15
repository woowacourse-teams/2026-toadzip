package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.SupplyType;
import java.time.YearMonth;
import java.math.BigDecimal;
import java.util.List;

public record SupplyRowResponse(
        long supplyRowId,
        // 공고문의 단지명(공고 단지 이름)과 신청형 이름. 원천 시절 이름 열을 지운 뒤로 응답 모양만 남겼다.
        String sourceComplexName,
        String sourceHousingTypeName,
        SupplyComplexResponse complex,
        HousingTypeResponse housingType,
        YearMonth occupancyExpectedYearMonth,
        SupplyType supplyType,
        Integer totalSupplyHouseholdCount,
        List<SupplyTargetResponse> targets,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        SharedRecruitment sharedRecruitment,
        BigDecimal exclusiveArea,
        BigDecimal supplyArea,
        boolean corrected,
        // 화면 표시 이름: 공고 공급표의 신청형 이름
        String displayHousingTypeName
) {

    public SupplyRowResponse {
        targets = List.copyOf(targets);
    }

    public record SharedRecruitment(long id, Integer householdCount) { }

    public SupplyRowResponse(long supplyRowId, String sourceComplexName, String sourceHousingTypeName,
            SupplyComplexResponse complex, HousingTypeResponse housingType, YearMonth occupancyExpectedYearMonth,
            SupplyType supplyType, Integer totalSupplyHouseholdCount, List<SupplyTargetResponse> targets) {
        this(supplyRowId, sourceComplexName, sourceHousingTypeName, complex, housingType, occupancyExpectedYearMonth,
                supplyType, totalSupplyHouseholdCount, targets, null, null, null, false, sourceHousingTypeName);
    }
}
