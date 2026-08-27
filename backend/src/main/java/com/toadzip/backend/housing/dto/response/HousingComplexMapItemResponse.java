package com.toadzip.backend.housing.dto.response;

import java.math.BigDecimal;

public record HousingComplexMapItemResponse(
        long complexId,
        String name,
        BigDecimal latitude,
        BigDecimal longitude,
        String rentalType,
        AgencyResponse agency,
        BigDecimal exclusiveAreaMin,
        BigDecimal exclusiveAreaMax,
        Long depositMin,
        Long depositMax,
        Long monthlyRentMin,
        Long monthlyRentMax
) {
}
