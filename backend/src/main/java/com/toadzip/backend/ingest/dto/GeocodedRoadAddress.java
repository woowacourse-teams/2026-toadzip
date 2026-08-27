package com.toadzip.backend.ingest.dto;

import java.math.BigDecimal;

import com.toadzip.backend.ingest.domain.Wgs84Coordinate;

public record GeocodedRoadAddress(String roadAddress, BigDecimal latitude, BigDecimal longitude) {

    public static GeocodedRoadAddress of(String roadAddress, Wgs84Coordinate coordinate) {
        return new GeocodedRoadAddress(
                roadAddress,
                coordinate.latitude(),
                coordinate.longitude()
        );
    }
}
