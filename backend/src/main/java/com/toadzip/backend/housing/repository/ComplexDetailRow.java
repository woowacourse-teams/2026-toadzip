package com.toadzip.backend.housing.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ComplexDetailRow(
        long complexId,
        String name,
        String imageUrl,
        String provinceCode,
        String cityCountyDistrictCode,
        String roadAddress,
        String rentalType,
        String agencyCode,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalDate completionDate,
        String buildingType,
        Boolean hasElevator,
        String heatingType,
        String corridorType,
        Integer moveOutCountLastYear,
        int totalHouseholdCount,
        int totalParkingCount
) {
}
