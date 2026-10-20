package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.SupplyType;
import java.time.YearMonth;
import java.math.BigDecimal;
import java.util.List;

public record SupplyRowResponse(
        long supplyRowId,
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
        boolean corrected
) {

    public SupplyRowResponse {
        targets = List.copyOf(targets);
    }

    public record SharedRecruitment(long id, Integer householdCount) { }

    public SupplyRowResponse(long supplyRowId, String sourceComplexName, String sourceHousingTypeName,
            SupplyComplexResponse complex, HousingTypeResponse housingType, YearMonth occupancyExpectedYearMonth,
            SupplyType supplyType, Integer totalSupplyHouseholdCount, List<SupplyTargetResponse> targets) {
        this(supplyRowId, sourceComplexName, sourceHousingTypeName, complex, housingType, occupancyExpectedYearMonth,
                supplyType, totalSupplyHouseholdCount, targets, null, null, null, false);
    }
}
