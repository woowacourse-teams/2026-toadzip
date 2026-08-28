package com.toadzip.backend.housing.repository;

import java.math.BigDecimal;

public record HousingTypeDetailRow(
        long housingTypeId,
        String name,
        BigDecimal exclusiveArea,
        BigDecimal supplyArea,
        String floorPlanImageUrl,
        Boolean isDuplex,
        BigDecimal maintenanceFee
) {
}
