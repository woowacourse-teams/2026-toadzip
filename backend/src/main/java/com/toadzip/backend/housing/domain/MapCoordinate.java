package com.toadzip.backend.housing.domain;

import java.math.BigDecimal;

public record MapCoordinate(BigDecimal latitude, BigDecimal longitude) {

    private static final BigDecimal MINIMUM_LATITUDE = BigDecimal.valueOf(-90);
    private static final BigDecimal MAXIMUM_LATITUDE = BigDecimal.valueOf(90);
    private static final BigDecimal MINIMUM_LONGITUDE = BigDecimal.valueOf(-180);
    private static final BigDecimal MAXIMUM_LONGITUDE = BigDecimal.valueOf(180);

    public MapCoordinate {
        requireRange(latitude, MINIMUM_LATITUDE, MAXIMUM_LATITUDE, "latitude");
        requireRange(longitude, MINIMUM_LONGITUDE, MAXIMUM_LONGITUDE, "longitude");
    }

    private static void requireRange(
            BigDecimal value,
            BigDecimal minimum,
            BigDecimal maximum,
            String name
    ) {
        if (value != null && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0) {
            return;
        }
        throw new IllegalArgumentException(name + " is outside the supported coordinate range");
    }
}
