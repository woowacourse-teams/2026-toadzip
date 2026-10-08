package com.toadzip.backend.housing.repository;

import java.math.BigDecimal;
import java.time.Instant;

public record HousingTypeDetailRow(
        long housingTypeId,
        String name,
        BigDecimal exclusiveArea,
        BigDecimal supplyArea,
        String floorPlanImageUrl,
        Boolean isDuplex,
        BigDecimal maintenanceFee,
        Integer totalHouseholdCount,
        Long basicDeposit,
        Long basicMonthlyRent,
        Instant rentalConditionCollectedAt,
        String sourceHousingTypeIdentifier
) {
}
