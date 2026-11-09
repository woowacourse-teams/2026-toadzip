package com.toadzip.backend.ingest.location.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoadNumberAddressTest {

    @Test
    void 시도_긴_표기를_짧은_표기로_맞추고_구_표기는_비교하지_않는다() {
        assertThat(RoadNumberAddress.parse("충청북도 청주시 상당구 상당로 100-2"))
                .contains(new RoadNumberAddress("충북", "상당로", "100-2"));
        assertThat(RoadNumberAddress.parse("경기도 화성시 동탄구 동탄대로 100"))
                .isEqualTo(RoadNumberAddress.parse("경기 화성시 동탄대로 100"));
    }

    @Test
    void 시도나_건물번호가_없으면_주소_키를_만들지_않는다() {
        assertThat(RoadNumberAddress.parse("화성시 동탄대로 100")).isEmpty();
        assertThat(RoadNumberAddress.parse("경기도 화성시 동탄 A1블록")).isEmpty();
        assertThat(RoadNumberAddress.parse(" ")).isEmpty();
    }
}
