package com.toadzip.backend.housing.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HousingComplexTest {

    @Test
    void 공급유형별_단지를_생성한다() {
        Address address = createAddress();
        LocalDate completionDate = LocalDate.of(2020, 6, 30);
        Method createMethod = assertDoesNotThrow(
                () -> HousingComplex.class.getDeclaredMethod(
                        "create",
                        String.class,
                        String.class,
                        String.class,
                        Address.class,
                        int.class,
                        String.class,
                        LocalDate.class,
                        String.class,
                        String.class,
                        String.class,
                        boolean.class,
                        int.class,
                        String.class,
                        Integer.class
                )
        );

        HousingComplex housingComplex = assertDoesNotThrow(
                () -> (HousingComplex) createMethod.invoke(
                        null,
                        "두꺼비 행복주택",
                        "source-complex-id",
                        "행복주택",
                        address,
                        500,
                        "LH",
                        completionDate,
                        "개별난방",
                        "아파트",
                        "계단식",
                        true,
                        420,
                        "https://example.com/complex.png",
                        25
                )
        );

        assertEquals("두꺼비 행복주택", housingComplex.getName());
        assertEquals("source-complex-id", housingComplex.getSourceComplexIdentifier());
        assertEquals("행복주택", housingComplex.getSupplyType());
        assertEquals(address, housingComplex.getAddress());
        assertEquals(500, housingComplex.getTotalHouseholdCount());
        assertEquals("LH", housingComplex.getProvider());
        assertEquals(completionDate, housingComplex.getCompletionDate());
        assertEquals("개별난방", housingComplex.getHeatingType());
        assertEquals("아파트", housingComplex.getHousingType());
        assertEquals("계단식", housingComplex.getCorridorType());
        assertTrue(housingComplex.isElevatorInstalled());
        assertEquals(420, housingComplex.getParkingSpaceCount());
        assertEquals("https://example.com/complex.png", housingComplex.getImageUrl());
        assertEquals(25, housingComplex.getRecentOneYearMoveOutCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void 단지의_문자열_정보는_비어_있을_수_없다(int blankFieldIndex) {
        String[] fields = {
                "두꺼비 행복주택",
                "source-complex-id",
                "행복주택",
                "LH",
                "개별난방",
                "아파트",
                "계단식"
        };
        fields[blankFieldIndex] = " ";

        assertThrows(
                IllegalArgumentException.class,
                () -> createHousingComplex(
                        fields,
                        createAddress(),
                        500,
                        LocalDate.of(2020, 6, 30),
                        420,
                        25
                )
        );
    }

    @Test
    void 주소와_준공일은_필수다() {
        String[] fields = validStringFields();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingComplex(fields, null, 500, LocalDate.of(2020, 6, 30), 420, 25)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingComplex(fields, createAddress(), 500, null, 420, 25)
                )
        );
    }

    @Test
    void 세대수와_주차대수와_퇴거자수는_음수일_수_없다() {
        String[] fields = validStringFields();
        LocalDate completionDate = LocalDate.of(2020, 6, 30);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingComplex(fields, createAddress(), -1, completionDate, 420, 25)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingComplex(fields, createAddress(), 500, completionDate, -1, 25)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingComplex(fields, createAddress(), 500, completionDate, 420, -1)
                )
        );
    }

    private HousingComplex createHousingComplex(
            String[] fields,
            Address address,
            int totalHouseholdCount,
            LocalDate completionDate,
            int parkingSpaceCount,
            Integer recentOneYearMoveOutCount
    ) {
        return HousingComplex.create(
                fields[0],
                fields[1],
                fields[2],
                address,
                totalHouseholdCount,
                fields[3],
                completionDate,
                fields[4],
                fields[5],
                fields[6],
                true,
                parkingSpaceCount,
                "https://example.com/complex.png",
                recentOneYearMoveOutCount
        );
    }

    private String[] validStringFields() {
        return new String[]{
                "두꺼비 행복주택",
                "source-complex-id",
                "행복주택",
                "LH",
                "개별난방",
                "아파트",
                "계단식"
        };
    }

    private Address createAddress() {
        return Address.create(
                "서울특별시 중구 세종대로 110",
                "1114010100100010000",
                "1114010100",
                "11",
                "11140",
                new BigDecimal("37.5665"),
                new BigDecimal("126.9780")
        );
    }
}
