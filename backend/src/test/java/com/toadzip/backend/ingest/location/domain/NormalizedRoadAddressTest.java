package com.toadzip.backend.ingest.location.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NormalizedRoadAddressTest {

    @Test
    void 부번_0은_없는_부번과_비교하되_원천_표기는_유지한다() {
        var address = new NormalizedRoadAddress("인천광역시 남동구 선수촌로 27-0 (구월동)");

        assertThat(address.value()).isEqualTo("인천광역시 남동구 선수촌로 27-0 (구월동)");
        assertThat(address.locationKey()).isEqualTo("인천광역시 남동구 선수촌로 27");
        assertThat(new NormalizedRoadAddress("인천광역시 남동구 선수촌로 27").locationKey())
                .isEqualTo(address.locationKey());
        assertThat(address.withoutReference()).isEqualTo("인천광역시 남동구 선수촌로 27-0");
    }

    @Test
    void 실제_부번과_건물번호가_아닌_부분은_유지한다() {
        assertThat(new NormalizedRoadAddress("인천광역시 남동구 선수촌로 27-1").locationKey())
                .isNotEqualTo(new NormalizedRoadAddress("인천광역시 남동구 선수촌로 27").locationKey());
        assertThat(new NormalizedRoadAddress("인천광역시 남동구 선수촌로 27-01")
                .locationKey()).endsWith("27-01");
        assertThat(new NormalizedRoadAddress("인천광역시 남동구 테스트-0길 27")
                .locationKey()).endsWith("테스트-0길 27");
        assertThat(new NormalizedRoadAddress("인천광역시 남동구 선수촌로 27-0 상세")
                .locationKey()).endsWith("27-0 상세");
    }

    @Test
    void 지하_주소의_부번_0도_비교하되_지상과_구분한다() {
        var underground = new NormalizedRoadAddress("서울특별시 중구 세종대로 지하 110-0");
        assertThat(underground.locationKey())
                .isEqualTo(new NormalizedRoadAddress("서울특별시 중구 세종대로 지하 110").locationKey())
                .isNotEqualTo(new NormalizedRoadAddress("서울특별시 중구 세종대로 110").locationKey());
    }
}
