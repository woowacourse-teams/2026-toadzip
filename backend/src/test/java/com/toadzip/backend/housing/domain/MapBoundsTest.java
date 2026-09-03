package com.toadzip.backend.housing.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void 내부와_경계의_좌표를_포함하고_외부_좌표는_포함하지_않는다() {
        MapBounds bounds = MapBounds.of(
                decimal("37.0"), decimal("126.0"), decimal("38.0"), decimal("127.0")
        );

        assertTrue(bounds.contains(new MapCoordinate(decimal("37.5"), decimal("126.5"))));
        assertTrue(bounds.contains(new MapCoordinate(decimal("38.0"), decimal("127.0"))));
        assertFalse(bounds.contains(new MapCoordinate(decimal("38.1"), decimal("127.0"))));
        assertFalse(bounds.contains(null));
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
