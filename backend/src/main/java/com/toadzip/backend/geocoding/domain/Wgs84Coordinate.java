package com.toadzip.backend.geocoding.domain;

import java.math.BigDecimal;

public record Wgs84Coordinate(BigDecimal latitude, BigDecimal longitude) {

    private static final BigDecimal MIN_LATITUDE = BigDecimal.valueOf(-90);

    private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);

    private static final BigDecimal MIN_LONGITUDE = BigDecimal.valueOf(-180);

    private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);

    public Wgs84Coordinate {
        requireRange(latitude, MIN_LATITUDE, MAX_LATITUDE, "위도");
        requireRange(longitude, MIN_LONGITUDE, MAX_LONGITUDE, "경도");
    }

    private static void requireRange(BigDecimal value, BigDecimal minimum, BigDecimal maximum, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "는 필수입니다.");
        }
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(fieldName + " 범위가 올바르지 않습니다.");
        }
    }
}
