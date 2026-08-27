package com.toadzip.backend.housing.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ComplexSummaryRow(
        long complexId,
        String name,
        String imageUrl,
        String provinceCode,
        String cityCountyDistrictCode,
        String rentalType,
        String agencyCode,
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal exclusiveAreaMin,
        BigDecimal exclusiveAreaMax,
        BigDecimal depositMin,
        BigDecimal depositMax,
        BigDecimal monthlyRentMin,
        BigDecimal monthlyRentMax,
        Long announcementId,
        String publicationType,
        LocalDate postedDate,
        LocalDate applicationStartDate,
        LocalDate applicationEndDate
) {
}
