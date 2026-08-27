package com.toadzip.backend.housing.dto.response;

import java.math.BigDecimal;

public record HousingComplexAddressResponse(
        String regionName,
        String roadAddress,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
