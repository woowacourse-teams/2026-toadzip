package com.toadzip.backend.housing.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressTest {

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
                        "서울특별시",
                        "11140",
                        "중구",
                        latitude,
                        longitude
                )
        );

        assertEquals("서울특별시 중구 세종대로 110", address.getRoadAddress());
        assertEquals("1114010100100010000", address.getPnu());
        assertEquals("1114010100", address.getLegalDongCode());
        assertEquals("11", address.getProvinceCode());
        assertEquals("서울특별시", address.getProvinceName());
        assertEquals("11140", address.getCityCountyDistrictCode());
        assertEquals("중구", address.getCityCountyDistrictName());
        assertEquals(latitude, address.getLatitude());
        assertEquals(longitude, address.getLongitude());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void 주소의_문자열_정보는_비어_있을_수_없다(int blankFieldIndex) {
        String[] fields = {
                "서울특별시 중구 세종대로 110",
                "1114010100100010000",
                "1114010100",
                "11",
                "서울특별시",
                "11140",
                "중구"
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
                        fields[5],
                        fields[6],
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

    private Address createAddress(BigDecimal latitude, BigDecimal longitude) {
        return Address.create(
                "서울특별시 중구 세종대로 110",
                "1114010100100010000",
                "1114010100",
                "11",
                "서울특별시",
                "11140",
                "중구",
                latitude,
                longitude
        );
    }
}
