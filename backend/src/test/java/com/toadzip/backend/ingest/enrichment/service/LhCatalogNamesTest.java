package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LhCatalogNamesTest {

    @Test
    void 비교용_이름은_공백과_구분_기호를_지우고_소문자로_맞춘다() {
        assertThat(LhCatalogNames.normalized(" LH-1 (행복)·마을, A ")).isEqualTo("lh1행복마을a");
        assertThat(LhCatalogNames.normalized(null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "서울특별시 강남구, 서울강남구",
            "부산광역시, 부산",
            "대구광역시, 대구",
            "인천광역시, 인천",
            "광주광역시, 광주",
            "대전광역시, 대전",
            "울산광역시, 울산",
            "세종특별자치시, 세종",
            "강원특별자치도, 강원",
            "강원도, 강원",
            "전북특별자치도, 전북",
            "전라북도, 전북",
            "제주특별자치도, 제주",
            "경기도 수원시, 경기수원시",
            "충청북도, 충북",
            "충청남도, 충남",
            "전라남도, 전남",
            "경상북도, 경북",
            "경상남도, 경남"
    })
    void 지역은_시도_이름을_약칭으로_줄인다(String region, String expected) {
        assertThat(LhCatalogNames.region(region)).isEqualTo(expected);
    }
}
