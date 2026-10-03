package com.toadzip.backend.housing.dto.response;

import java.math.BigDecimal;

public record HousingComplexSearchBoundsResponse(
        BigDecimal southWestLat,
        BigDecimal southWestLng,
        BigDecimal northEastLat,
        BigDecimal northEastLng
) {
}
