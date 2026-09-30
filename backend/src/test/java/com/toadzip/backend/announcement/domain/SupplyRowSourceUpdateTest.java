package com.toadzip.backend.announcement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class SupplyRowSourceUpdateTest {

    @Test
    void 원천에서_변경된_공급행_정보를_갱신한다() {
        SupplyRow supplyRow = supplyRow("기존 단지");

        boolean updated = supplyRow.updateFromMyHome(
                null,
                null,
                2,
                "변경 단지",
                "아파트",
                "1111010100100010000",
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

        boolean updated = supplyRow.updateFromMyHome(
                null,
                null,
                1,
                "기존 단지",
                "아파트",
                "1111010100100010000",
                SupplyCategory.NEW_SUPPLY,
                null,
                20
        );

        assertThat(updated).isFalse();
    }

    @Test
    void 마이홈_재정제는_LH가_보강한_입주월과_세대수를_보존한다() {
        SupplyRow supplyRow = supplyRow("기존 단지");
        supplyRow.enrichFromLh("LH:100:SUPPLY:0", YearMonth.of(2028, 1), 100);

        boolean updated = supplyRow.updateFromMyHome(
                null,
                null,
                1,
                "기존 단지",
                "아파트",
                "1111010100100010000",
                SupplyCategory.NEW_SUPPLY,
                null,
                20
        );

        assertThat(updated).isFalse();
        assertThat(supplyRow.getExpectedMoveInMonth()).isEqualTo(YearMonth.of(2028, 1));
        assertThat(supplyRow.getTotalSupplyHouseholdCount()).isEqualTo(100);
    }

    @Test
    void 같은_LH_공급행의_모집세대수는_상세_보강_전까지_변경을_반영한다() {
        SupplyRow supplyRow = supplyRow("기존 단지");
        supplyRow.resolveFromLhSupply("LH:100:SUPPLY:0", 20, 20);

        boolean updated = supplyRow.resolveFromLhSupply("LH:100:SUPPLY:0", 30, 30);

        assertThat(updated).isTrue();
        assertThat(supplyRow.getTotalSupplyHouseholdCount()).isEqualTo(30);
        assertThat(supplyRow.isLhTotalSupplyHouseholdCountOwned()).isTrue();
        assertThat(supplyRow.isLhTotalSupplyHouseholdCountEnriched()).isFalse();
    }

    @Test
    void LH_상세가_보강한_전체세대수는_공급행의_모집세대수로_덮지_않는다() {
        SupplyRow supplyRow = supplyRow("기존 단지");
        supplyRow.resolveFromLhSupply("LH:100:SUPPLY:0", 20, 20);
        supplyRow.enrichFromLh("LH:100:SUPPLY:0", null, 100);

        boolean updated = supplyRow.resolveFromLhSupply("LH:100:SUPPLY:0", 30, 30);

        assertThat(updated).isFalse();
        assertThat(supplyRow.getTotalSupplyHouseholdCount()).isEqualTo(100);
        assertThat(supplyRow.isLhTotalSupplyHouseholdCountEnriched()).isTrue();
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
