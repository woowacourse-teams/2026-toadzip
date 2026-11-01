package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SupplyRowHousingTypesTest {

    private final HousingComplex complex = HousingComplex.create("두꺼비 행복주택", "source-complex-id", "행복주택",
            Address.create("서울특별시 중구 세종대로 110", "1114010100100010000", "1114010100", "11", "11140",
                    new BigDecimal("37.5665"), new BigDecimal("126.9780")),
            500, "LH", LocalDate.of(2020, 6, 30), "개별난방", "아파트", "계단식", true, 420,
            "https://example.com/complex.png", 25);
    private final HousingType area59 = HousingType.createFromMyHome(complex, "59", "59A", new BigDecimal("59.98"), null);
    private final HousingType area84 = HousingType.createFromMyHome(complex, "84", "84A", new BigDecimal("84.96"), null);

    @Test
    void 함께_모집하는_주택형은_대표_주택형을_따라간다() {
        SupplyRow row = SupplyRow.create(new Announcement(), complex, area59, "row-1", 0, "두꺼비 행복주택", "59A",
                "1114010100100010000", null, SupplyCategory.NEW_SUPPLY, null, 10);
        assertThat(row.getHousingTypes()).containsExactly(area59);

        row.updateFromMyHome(complex, area84, 0, "두꺼비 행복주택", "59A", "1114010100100010000",
                SupplyCategory.NEW_SUPPLY, null, 10);
        assertThat(row.getHousingType()).isSameAs(area84);
        assertThat(row.getHousingTypes()).containsExactly(area84);

        row.updateFromMyHome(complex, null, 0, "두꺼비 행복주택", "59A", "1114010100100010000",
                SupplyCategory.NEW_SUPPLY, "일치하는 주택형이 없습니다.", 10);
        assertThat(row.getHousingTypes()).isEmpty();
    }

    @Test
    void 관리자가_고친_공급행을_저장한_매칭으로_다시_연결해도_함께_모집하는_주택형이_따라간다() {
        ReflectionTestUtils.setField(area59, "id", 59L);
        ReflectionTestUtils.setField(area84, "id", 84L);
        SupplyRow row = SupplyRow.create(new Announcement(), complex, area59, "row-1", 0, "두꺼비 행복주택", "59A",
                "1114010100100010000", null, SupplyCategory.NEW_SUPPLY, null, 10);

        assertThat(row.relinkByAdminMatch(complex, area84)).isTrue();
        assertThat(row.getHousingTypes()).containsExactly(area84);
    }

    @Test
    void 대표_주택형이_없는_공급행은_함께_모집하는_주택형도_없다() {
        SupplyRow row = SupplyRow.create(new Announcement(), null, null, "row-1", 0, "두꺼비 행복주택", "59A",
                "1114010100100010000", null, SupplyCategory.NEW_SUPPLY, "일치하는 단지가 없습니다.", 10);
        assertThat(row.getHousingTypes()).isEmpty();
    }
}
