package com.toadzip.backend.housing.service;

import java.math.BigDecimal;

import com.toadzip.backend.housing.exception.InvalidMapBoundsException;

public record MapBounds(
        BigDecimal southWestLat,
        BigDecimal southWestLng,
        BigDecimal northEastLat,
        BigDecimal northEastLng
) {

    private static final BigDecimal MIN_LATITUDE = BigDecimal.valueOf(-90);

    private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);

    private static final BigDecimal MIN_LONGITUDE = BigDecimal.valueOf(-180);

    private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);

    public static MapBounds of(
            BigDecimal southWestLat,
            BigDecimal southWestLng,
            BigDecimal northEastLat,
            BigDecimal northEastLng
    ) {
        requireAll(southWestLat, southWestLng, northEastLat, northEastLng);
        requireRange(southWestLat, MIN_LATITUDE, MAX_LATITUDE);
        requireRange(northEastLat, MIN_LATITUDE, MAX_LATITUDE);
        requireRange(southWestLng, MIN_LONGITUDE, MAX_LONGITUDE);
        requireRange(northEastLng, MIN_LONGITUDE, MAX_LONGITUDE);
        requireAscending(southWestLat, northEastLat);
        requireAscending(southWestLng, northEastLng);
        return new MapBounds(southWestLat, southWestLng, northEastLat, northEastLng);
    }

    private static void requireAll(
            BigDecimal southWestLat,
            BigDecimal southWestLng,
            BigDecimal northEastLat,
            BigDecimal northEastLng
    ) {
        if (southWestLat == null || southWestLng == null || northEastLat == null || northEastLng == null) {
            throw new InvalidMapBoundsException();
        }
    }

    private static void requireRange(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new InvalidMapBoundsException();
        }
    }

    private static void requireAscending(BigDecimal start, BigDecimal end) {
        if (start.compareTo(end) > 0) {
            throw new InvalidMapBoundsException();
        }
    }
}
