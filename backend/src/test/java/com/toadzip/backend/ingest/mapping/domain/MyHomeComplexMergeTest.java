package com.toadzip.backend.ingest.mapping.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MyHomeComplexMergeTest {

    @Test
    void 통합과_복구_시각은_PostgreSQL에_저장되는_정밀도로_유지한다() {
        Instant mergedAt = Instant.parse("2026-09-28T02:34:07.123456789Z");
        Instant revertedAt = Instant.parse("2026-09-28T02:35:08.987654321Z");
        MyHomeComplexMerge merge = MyHomeComplexMerge.verified(
                UUID.randomUUID(), List.of(1L, 2L), 11, "{}", "{}", "preview-hash", "확인 근거",
                "admin", mergedAt);

        assertThat(merge.getMergedAt()).isEqualTo(mergedAt.truncatedTo(ChronoUnit.MICROS));

        merge.revert("admin", revertedAt);

        assertThat(merge.getRevertedAt()).isEqualTo(revertedAt.truncatedTo(ChronoUnit.MICROS));
    }
}
