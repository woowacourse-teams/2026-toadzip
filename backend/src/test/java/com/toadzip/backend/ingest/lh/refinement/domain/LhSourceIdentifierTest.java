package com.toadzip.backend.ingest.lh.refinement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class LhSourceIdentifierTest {

    @Test
    void LH_원천_식별자는_공고_종류_순번_순서로_만든다() {
        assertThat(LhSourceIdentifier.source("PAN-1", "SCHEDULE", 2)).isEqualTo("LH:PAN-1:SCHEDULE:2");
        assertThat(LhSourceIdentifier.supply("PAN-1", 0)).isEqualTo("LH:PAN-1:SUPPLY:0");
    }

    @Test
    void 지정한_LH_공고의_원천만_고르고_공고_번호가_앞부분만_같은_공고는_제외한다() {
        assertThat(LhSourceIdentifier.isLhSourceOfAny("LH:PAN-1:SUPPLY:0", Set.of("PAN-1"))).isTrue();
        assertThat(LhSourceIdentifier.isLhSourceOfAny("LH:PAN-10:SUPPLY:0", Set.of("PAN-1"))).isFalse();
        assertThat(LhSourceIdentifier.isLhSourceOfAny(null, Set.of("PAN-1"))).isFalse();
    }

    @Test
    void 공고에_LH_공급으로_만든_공급행은_그_공고의_것으로만_판정한다() {
        String row = LhSourceIdentifier.supplyRow("21026", "PAN-1", 3);

        assertThat(row).isEqualTo("21026:LH:PAN-1:3");
        assertThat(LhSourceIdentifier.isSupplyRowOf("21026", row)).isTrue();
        assertThat(LhSourceIdentifier.isSupplyRowOf("2102", row)).isFalse();
        assertThat(LhSourceIdentifier.isSupplyRowOf("21026", "21026")).isFalse();
    }

    @Test
    void LH_원천_소유_공고의_공급행_키는_공고_단지_주택형명_순번으로_만들고_LH_원천으로_보지_않는다() {
        String row = LhSourceIdentifier.lhOwnedSupplyRow(12L, "59A(청년)", 2);

        assertThat(row).isEqualTo("LHROW:12:59A(청년)#2");
        assertThat(LhSourceIdentifier.isLhSourceOfAny(row, Set.of("12"))).isFalse();
    }

    @Test
    void LH_공급_원천_식별자만_LH_공고와_순번으로_해석한다() {
        assertThat(LhSourceIdentifier.parseSupply(LhSourceIdentifier.supply("PAN-77", 9)))
                .contains(new LhSourceIdentifier.Supply("PAN-77", "9"));
        assertThat(LhSourceIdentifier.parseSupply("LH:PAN-77:DETAIL:1")).isEmpty();
        assertThat(LhSourceIdentifier.parseSupply("SH:PAN-77:SUPPLY:1")).isEmpty();
        assertThat(LhSourceIdentifier.parseSupply("LH:PAN-77:SUPPLY")).isEmpty();
        assertThat(LhSourceIdentifier.parseSupply("21026:LH:PAN-77:1")).isEmpty();
        assertThat(LhSourceIdentifier.parseSupply(null)).isEmpty();
    }
}
