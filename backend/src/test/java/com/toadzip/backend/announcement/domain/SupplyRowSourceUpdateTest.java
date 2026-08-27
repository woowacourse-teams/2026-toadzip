package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class SupplyRowSourceUpdateTest {

    @Test
    void 원천에서_변경된_공급행_정보를_갱신한다() {
        SupplyRow supplyRow = supplyRow("기존 단지");

        boolean updated = supplyRow.updateFromSource(
                null,
                null,
                2,
                "변경 단지",
                "아파트",
                "1111010100100010000",
                null,
                SupplyCategory.RESUPPLY,
                "일치하는 단지가 없습니다.",
                30
        );

        assertThat(updated).isTrue();
        assertThat(supplyRow.getDisplayOrder()).isEqualTo(2);
        assertThat(supplyRow.getSourceComplexName()).isEqualTo("변경 단지");
        assertThat(supplyRow.getSupplyCategory()).isEqualTo(SupplyCategory.RESUPPLY);
        assertThat(supplyRow.getMatchingFailureReason()).isEqualTo("일치하는 단지가 없습니다.");
        assertThat(supplyRow.getTotalSupplyHouseholdCount()).isEqualTo(30);
    }

    @Test
    void 원천_공급행_정보가_같으면_갱신하지_않는다() {
        SupplyRow supplyRow = supplyRow("기존 단지");

        boolean updated = supplyRow.updateFromSource(
                null,
                null,
                1,
                "기존 단지",
                "아파트",
                "1111010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                null,
                20
        );

        assertThat(updated).isFalse();
    }

    private SupplyRow supplyRow(String complexName) {
        return SupplyRow.create(
                new Announcement(),
                null,
                null,
                "source-supply-row-id",
                1,
                complexName,
                "아파트",
                "1111010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                null,
                20
        );
    }
}
