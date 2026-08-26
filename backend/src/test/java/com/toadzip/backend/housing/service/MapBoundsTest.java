package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.toadzip.backend.housing.exception.InvalidMapBoundsException;

class MapBoundsTest {

    @Test
    void 네_좌표가_모두_있고_남서쪽이_북동쪽보다_작으면_경계를_생성한다() {
        MapBounds bounds = MapBounds.of(decimal("37.0"), decimal("126.0"), decimal("38.0"), decimal("127.0"));

        assertEquals(decimal("37.0"), bounds.southWestLat());
        assertEquals(decimal("126.0"), bounds.southWestLng());
        assertEquals(decimal("38.0"), bounds.northEastLat());
        assertEquals(decimal("127.0"), bounds.northEastLng());
    }

    @ParameterizedTest
    @MethodSource("invalidBounds")
    void 누락_범위초과_역전_좌표를_거부한다(
            BigDecimal southWestLat,
            BigDecimal southWestLng,
            BigDecimal northEastLat,
            BigDecimal northEastLng
    ) {
        assertThrows(
                InvalidMapBoundsException.class,
                () -> MapBounds.of(southWestLat, southWestLng, northEastLat, northEastLng)
        );
    }

    private static Stream<Arguments> invalidBounds() {
        return Stream.of(
                Arguments.of(null, decimal("126.0"), decimal("38.0"), decimal("127.0")),
                Arguments.of(decimal("-90.1"), decimal("126.0"), decimal("38.0"), decimal("127.0")),
                Arguments.of(decimal("37.0"), decimal("180.1"), decimal("38.0"), decimal("127.0")),
                Arguments.of(decimal("37.0"), decimal("126.0"), decimal("37.0"), decimal("127.0")),
                Arguments.of(decimal("37.0"), decimal("126.0"), decimal("38.0"), decimal("126.0")),
                Arguments.of(decimal("38.0"), decimal("126.0"), decimal("37.0"), decimal("127.0")),
                Arguments.of(decimal("37.0"), decimal("127.0"), decimal("38.0"), decimal("126.0"))
        );
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
