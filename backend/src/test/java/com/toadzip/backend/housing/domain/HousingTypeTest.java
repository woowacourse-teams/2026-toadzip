package com.toadzip.backend.housing.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class HousingTypeTest {

    @Test
    void LH_주택형_세대수만_보강하고_같은_값은_변경하지_않는다() {
        HousingType housingType = HousingType.createFromMyHome(
                createHousingComplex(),
                "source-housing-type-id",
                "46A",
                new BigDecimal("46.8000"),
                null
        );

        assertTrue(housingType.enrichHouseholdCountFromLh(125));
        assertEquals(125, housingType.getTotalHouseholdCount());
        assertFalse(housingType.enrichHouseholdCountFromLh(125));
    }

    @Test
    void LH_주택형_세대수는_음수로_보강할_수_없다() {
        HousingType housingType = HousingType.createFromMyHome(
                createHousingComplex(),
                "source-housing-type-id",
                "46A",
                new BigDecimal("46.8000"),
                null
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> housingType.enrichHouseholdCountFromLh(-1)
        );
    }

    @Test
    void 마이홈에_없는_주택형_정보는_미확정으로_생성한다() {
        HousingType housingType = HousingType.createFromMyHome(
                createHousingComplex(),
                "source-housing-type-id",
                "46A",
                new BigDecimal("46.8000"),
                new BigDecimal("67.0000")
        );

        assertAll(
                () -> assertEquals("source-housing-type-id", housingType.getSourceHousingTypeIdentifier()),
                () -> assertNull(housingType.getTotalHouseholdCount()),
                () -> assertNull(housingType.getFloorPlanUrl()),
                () -> assertNull(housingType.getDuplex())
        );
    }

    @Test
    void 단지에_속한_주택형을_생성한다() {
        HousingComplex housingComplex = createHousingComplex();
        BigDecimal exclusiveArea = new BigDecimal("36.00");
        BigDecimal supplyArea = new BigDecimal("48.00");
        BigDecimal maintenanceFee = new BigDecimal("120000");
        Method createMethod = assertDoesNotThrow(
                () -> HousingType.class.getDeclaredMethod(
                        "create",
                        HousingComplex.class,
                        String.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        int.class,
                        String.class,
                        boolean.class,
                        BigDecimal.class
                )
        );

        HousingType housingType = assertDoesNotThrow(
                () -> (HousingType) createMethod.invoke(
                        null,
                        housingComplex,
                        "36A",
                        exclusiveArea,
                        supplyArea,
                        120,
                        "https://example.com/floor-plan.png",
                        false,
                        maintenanceFee
                )
        );

        assertEquals(housingComplex, housingType.getHousingComplex());
        assertEquals("36A", housingType.getName());
        assertEquals(exclusiveArea, housingType.getExclusiveArea());
        assertEquals(supplyArea, housingType.getSupplyArea());
        assertEquals(120, housingType.getTotalHouseholdCount());
        assertEquals("https://example.com/floor-plan.png", housingType.getFloorPlanUrl());
        assertFalse(housingType.isDuplex());
        assertEquals(maintenanceFee, housingType.getMaintenanceFee());
    }

    @Test
    void 소속_단지와_주택형명과_평면도는_필수다() {
        HousingComplex housingComplex = createHousingComplex();
        BigDecimal area = new BigDecimal("36.00");

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingType(null, "36A", area, area, 120, "floor-plan", area)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingType(housingComplex, " ", area, area, 120, "floor-plan", area)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingType(housingComplex, "36A", area, area, 120, " ", area)
                )
        );
    }

    @Test
    void 전용면적은_필수다() {
        HousingComplex housingComplex = createHousingComplex();
        BigDecimal area = new BigDecimal("36.00");

        assertThrows(
                IllegalArgumentException.class,
                () -> createHousingType(housingComplex, "36A", null, area, 120, "floor-plan", area)
        );
    }

    @Test
    void 면적과_세대수와_관리비는_음수일_수_없다() {
        HousingComplex housingComplex = createHousingComplex();
        BigDecimal area = new BigDecimal("36.00");
        BigDecimal negative = BigDecimal.ONE.negate();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingType(housingComplex, "36A", negative, area, 120,
                                "floor-plan", area)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingType(housingComplex, "36A", area, negative, 120,
                                "floor-plan", area)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingType(housingComplex, "36A", area, area, -1,
                                "floor-plan", area)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> createHousingType(housingComplex, "36A", area, area, 120,
                                "floor-plan", negative)
                )
        );
    }

    private HousingType createHousingType(
            HousingComplex housingComplex,
            String name,
            BigDecimal exclusiveArea,
            BigDecimal supplyArea,
            int totalHouseholdCount,
            String floorPlanUrl,
            BigDecimal maintenanceFee
    ) {
        return HousingType.create(
                housingComplex,
                name,
                exclusiveArea,
                supplyArea,
                totalHouseholdCount,
                floorPlanUrl,
                false,
                maintenanceFee
        );
    }

    private HousingComplex createHousingComplex() {
        return HousingComplex.create(
                "두꺼비 행복주택",
                "source-complex-id",
                "행복주택",
                Address.create(
                        "서울특별시 중구 세종대로 110",
                        "1114010100100010000",
                        "1114010100",
                        "11",
                        "11140",
                        new BigDecimal("37.5665"),
                        new BigDecimal("126.9780")
                ),
                500,
                "LH",
                LocalDate.of(2020, 6, 30),
                "개별난방",
                "아파트",
                "계단식",
                true,
                420,
                "https://example.com/complex.png",
                25
        );
    }
}
