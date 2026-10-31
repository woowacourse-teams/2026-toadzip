package com.toadzip.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceTextTest {

    @Test
    void 앞뒤_공백을_지우고_비어_있으면_값이_없는_것으로_본다() {
        assertThat(SourceText.strippedOrNull(" 행복 단지 ")).isEqualTo("행복 단지");
        assertThat(SourceText.strippedOrNull("  ")).isNull();
        assertThat(SourceText.strippedOrNull(null)).isNull();
    }
}
