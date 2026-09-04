package com.toadzip.backend.housing.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record HousingTypeDetailResponse(
        long housingTypeId,
        String name,
        BigDecimal exclusiveArea,
        BigDecimal supplyArea,
        String floorPlanImageUrl,
        String floorPlan3dImageUrl,
        Boolean isDuplex,
        Long maintenanceFee,
        List<CurrentSupplyConditionResponse> currentSupplyConditions
) {

    public HousingTypeDetailResponse {
        currentSupplyConditions = List.copyOf(currentSupplyConditions);
    }
}
