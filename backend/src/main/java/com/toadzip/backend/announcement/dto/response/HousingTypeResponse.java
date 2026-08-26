package com.toadzip.backend.announcement.dto.response;

import java.math.BigDecimal;

public record HousingTypeResponse(
        long housingTypeId,
        String name,
        BigDecimal exclusiveArea,
        BigDecimal supplyArea,
        String floorPlanImageUrl,
        String floorPlan3dImageUrl
) {
}
