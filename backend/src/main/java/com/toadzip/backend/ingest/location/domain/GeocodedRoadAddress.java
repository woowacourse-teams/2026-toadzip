package com.toadzip.backend.ingest.location.domain;

import java.math.BigDecimal;

public record GeocodedRoadAddress(String roadAddress, BigDecimal latitude, BigDecimal longitude) {

    public static GeocodedRoadAddress of(String roadAddress, Wgs84Coordinate coordinate) {
        return new GeocodedRoadAddress(
                roadAddress,
                coordinate.latitude(),
                coordinate.longitude()
        );
    }
}
