package com.toadzip.backend.housing.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressTest {

    @Test
    void 위도와_경도_컬럼은_null을_허용하지_않는다() throws NoSuchFieldException {
        Field latitude = Address.class.getDeclaredField("latitude");
        Field longitude = Address.class.getDeclaredField("longitude");

        assertAll(
                () -> assertFalse(latitude.getAnnotation(Column.class).nullable()),
                () -> assertFalse(longitude.getAnnotation(Column.class).nullable())
        );
    }

    @Test
    void 위도와_경도를_컬럼_정밀도로_반올림한다() {
        Address address = createAddress(
                new BigDecimal("37.56620552"),
                new BigDecimal("126.97770648")
        );

        assertAll(
                () -> assertEquals(new BigDecimal("37.566206"), address.getLatitude()),
                () -> assertEquals(new BigDecimal("126.977706"), address.getLongitude())
        );
    }

    @Test
    void 단지_주소를_생성한다() {
        BigDecimal latitude = new BigDecimal("37.5665");
        BigDecimal longitude = new BigDecimal("126.9780");
        Method createMethod = assertDoesNotThrow(
                () -> Address.class.getDeclaredMethod(
                        "create",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        BigDecimal.class,
                        BigDecimal.class
                )
        );

        Address address = assertDoesNotThrow(
                () -> (Address) createMethod.invoke(
                        null,
                        "서울특별시 중구 세종대로 110",
                        "1114010100100010000",
                        "1114010100",
                        "11",
                        "11140",
                        latitude,
                        longitude
                )
        );

        assertEquals("서울특별시 중구 세종대로 110", address.getRoadAddress());
        assertEquals("1114010100100010000", address.getPnu());
        assertEquals("1114010100", address.getLegalDongCode());
        assertEquals("11", address.getProvinceCode());
        assertEquals("11140", address.getCityCountyDistrictCode());
        assertEquals(latitude.setScale(6), address.getLatitude());
        assertEquals(longitude.setScale(6), address.getLongitude());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void 주소의_문자열_정보는_비어_있을_수_없다(int blankFieldIndex) {
        String[] fields = {
                "서울특별시 중구 세종대로 110",
                "1114010100100010000",
                "1114010100",
                "11",
                "11140"
        };
        fields[blankFieldIndex] = " ";

        assertThrows(
                IllegalArgumentException.class,
                () -> Address.create(
                        fields[0],
                        fields[1],
                        fields[2],
                        fields[3],
                        fields[4],
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780")
                )
        );
    }

    @Test
    void 위도와_경도는_필수다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> createAddress(null, new BigDecimal("126.9780"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> createAddress(new BigDecimal("37.5665"), null)
        );
    }

    @Test
    void 위도는_영하_90도에서_90도_사이여야_한다() {
        assertAll(
                () -> assertDoesNotThrow(() -> createAddress(new BigDecimal("-90"), BigDecimal.ZERO)),
                () -> assertDoesNotThrow(() -> createAddress(new BigDecimal("90"), BigDecimal.ZERO)),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createAddress(new BigDecimal("-90.0001"), BigDecimal.ZERO)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createAddress(new BigDecimal("90.0001"), BigDecimal.ZERO)
                )
        );
    }

    @Test
    void 경도는_영하_180도에서_180도_사이여야_한다() {
        assertAll(
                () -> assertDoesNotThrow(() -> createAddress(BigDecimal.ZERO, new BigDecimal("-180"))),
                () -> assertDoesNotThrow(() -> createAddress(BigDecimal.ZERO, new BigDecimal("180"))),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createAddress(BigDecimal.ZERO, new BigDecimal("-180.0001"))
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createAddress(BigDecimal.ZERO, new BigDecimal("180.0001"))
                )
        );
    }

    private Address createAddress(BigDecimal latitude, BigDecimal longitude) {
        return Address.create(
                "서울특별시 중구 세종대로 110",
                "1114010100100010000",
                "1114010100",
                "11",
                "11140",
                latitude,
                longitude
        );
    }
}
