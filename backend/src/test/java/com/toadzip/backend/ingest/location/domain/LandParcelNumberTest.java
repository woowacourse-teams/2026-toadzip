package com.toadzip.backend.ingest.location.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LandParcelNumberTest {

    @Test
    void 법정동코드와_산_구분과_본번_부번으로_19자리를_만든다() {
        assertThat(LandParcelNumber.of("2823710100", false, 65, 17)).isEqualTo("2823710100100650017");
        assertThat(LandParcelNumber.of("2823710100", true, 3, 0)).isEqualTo("2823710100200030000");
    }

    @Test
    void 법정동코드나_지번이_형식에_맞지_않으면_거절한다() {
        assertThatThrownBy(() -> LandParcelNumber.of("28237", false, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LandParcelNumber.of("2823710100", false, 10000, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
