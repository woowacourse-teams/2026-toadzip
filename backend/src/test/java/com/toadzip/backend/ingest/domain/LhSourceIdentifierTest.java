package com.toadzip.backend.ingest.domain;

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
    void LH_원천은_LH_접두어로만_판정한다() {
        assertThat(LhSourceIdentifier.isLhSource("LH:PAN-1:SUPPLY:0")).isTrue();
        assertThat(LhSourceIdentifier.isLhSource("LHX:PAN-1")).isFalse();
        assertThat(LhSourceIdentifier.isLhSource(null)).isFalse();
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
}
