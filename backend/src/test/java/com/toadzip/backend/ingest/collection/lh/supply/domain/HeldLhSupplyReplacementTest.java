package com.toadzip.backend.ingest.collection.lh.supply.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeldLhSupplyReplacementTest {

    private static final String FINGERPRINT = "d".repeat(64);

    @Test
    void 이미_저장된_보류_실패의_오류_유형_이름으로_찾는다() {
        assertThat(HeldLhSupplyReplacement.errorTypes())
                .containsExactly("IncompleteLhSupplyReplacementException", "EmptyLhSupplyReplacementException");
    }

    @Test
    void 공급_감소_사유는_기존_실패_행과_같은_형식으로_제안_지문을_남긴다() {
        String reason = HeldLhSupplyReplacement.incompleteReason(3, FINGERPRINT);

        assertThat(reason).isEqualTo(
                "LH 공급 수집 결과에 기존 공급행 3건이 누락되어 원천을 교체하지 않습니다. proposedFingerprint=" + FINGERPRINT);
        assertThat(HeldLhSupplyReplacement.proposedFingerprint("IncompleteLhSupplyReplacementException", reason))
                .contains(FINGERPRINT);
    }
}
