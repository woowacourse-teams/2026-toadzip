package com.toadzip.backend.housing.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class HousingTypeAreasTest {

    private final HousingComplex complex = complex();

    @Test
    void 전용면적은_소수_둘째_자리까지_같으면_같은_주택형이다() {
        HousingType type = type(1L, "26", "26.3700");

        assertEquals(type, HousingTypeAreas.pick(List.of(type), new BigDecimal("26.374"), null).orElseThrow());
        assertTrue(HousingTypeAreas.pick(List.of(type), new BigDecimal("26.38"), null).isEmpty());
    }

    @Test
    void 같은_면적_주택형이_여럿이면_이름이_같은_것을_고른다() {
        HousingType first = type(3L, "101A1", "101.99");
        HousingType named = type(7L, "101A9", "101.99");

        assertEquals(named, HousingTypeAreas.pick(List.of(first, named), new BigDecimal("101.99"), "101A9형").orElseThrow());
    }

    @Test
    void 같은_면적_주택형이_여럿이고_이름이_다르면_가장_먼저_만든_주택형을_고른다() {
        HousingType later = type(9L, "84B", "84.96");
        HousingType earlier = type(4L, "84E", "84.96");

        assertEquals(earlier, HousingTypeAreas.pick(List.of(later, earlier), new BigDecimal("84.96"), "84.9600A")
                .orElseThrow());
    }

    @Test
    void 면적은_셋째_자리에서_반올림하고_이름은_공백_대소문자_형_표기를_무시한다() {
        assertEquals(new BigDecimal("26.38"), HousingTypeAreas.key(new BigDecimal("26.375")));
        // 오사오입(HALF_EVEN)이면 26.36이 된다. 화면·워커와 같은 HALF_UP이어야 미리 보인 주택형과 서버가 고른 주택형이 같다.
        assertEquals(new BigDecimal("26.37"), HousingTypeAreas.key(new BigDecimal("26.365")));
        HousingType first = type(1L, "26B", "26.38");
        HousingType named = type(2L, "26a", "26.38");

        assertEquals(named, HousingTypeAreas.pick(List.of(first, named), new BigDecimal("26.375"), "26A 형").orElseThrow());
    }

    @Test
    void 면적이_없으면_고르지_않는다() {
        assertTrue(HousingTypeAreas.pick(List.of(type(1L, "26", "26.37")), null, "26").isEmpty());
    }

    private HousingType type(long id, String name, String area) {
        HousingType type = HousingType.createFromMyHome(complex, "source-" + id, name, new BigDecimal(area), null);
        ReflectionTestUtils.setField(type, "id", id);
        return type;
    }

    private static HousingComplex complex() {
        return HousingComplex.create("두꺼비 행복주택", "source-complex-id", "행복주택",
                Address.create("서울특별시 중구 세종대로 110", "1114010100100010000", "1114010100", "11", "11140",
                        new BigDecimal("37.5665"), new BigDecimal("126.9780")),
                500, "LH", LocalDate.of(2020, 6, 30), "개별난방", "아파트", "계단식", true, 420,
                "https://example.com/complex.png", 25);
    }
}
