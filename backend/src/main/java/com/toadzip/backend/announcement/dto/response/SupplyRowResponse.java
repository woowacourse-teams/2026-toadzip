package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.SupplyType;
import java.time.YearMonth;
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
        List<SupplyTargetResponse> targets
) {

    public SupplyRowResponse {
        targets = List.copyOf(targets);
    }
}
