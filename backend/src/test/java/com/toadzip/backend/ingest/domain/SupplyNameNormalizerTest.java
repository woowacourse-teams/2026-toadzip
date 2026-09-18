package com.toadzip.backend.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupplyNameNormalizerTest {

    @Test
    void 임대유형_접미어와_공백_하이픈_차이를_제거한다() {
        assertThat(SupplyNameNormalizer.sameComplex("중동한라1 영구임대주택", "중동한라-1"))
                .isTrue();
    }

    @Test
    void 단지_번호와_블록_번호는_보존한다() {
        assertThat(SupplyNameNormalizer.sameComplex("중동한라1", "중동한라2 영구임대주택"))
                .isFalse();
        assertThat(SupplyNameNormalizer.sameComplex("고양삼송 A-1블록", "고양삼송 A-2블록"))
                .isFalse();
    }

    @Test
    void 주택형_구분은_보존하고_표기_접미어만_제거한다() {
        assertThat(SupplyNameNormalizer.sameHousingType("26A형", "26A 주택형"))
                .isTrue();
        assertThat(SupplyNameNormalizer.sameHousingType("26A형", "26B형"))
                .isFalse();
    }

    @Test
    void 정규화_결과가_빈_이름끼리는_같다고_판단하지_않는다() {
        assertThat(SupplyNameNormalizer.sameComplex("영구임대주택", "영구임대"))
                .isFalse();
        assertThat(SupplyNameNormalizer.sameHousingType("형", "주택형"))
                .isFalse();
    }
}
