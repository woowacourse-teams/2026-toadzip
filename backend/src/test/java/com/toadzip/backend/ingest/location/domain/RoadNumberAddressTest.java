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

    @Test
    void 번호_뒤에_가나_숫자가_이어지는_동_이름은_도로명_건물번호로_읽지_않는다() {
        assertThat(RoadNumberAddress.parse("서울특별시 종로구 종로1가 54")).isEmpty();
        assertThat(RoadNumberAddress.parse("서울특별시 중구 을지로3가")).isEmpty();
        assertThat(RoadNumberAddress.parse("서울 중구 충무로2가 12-1")).isEmpty();
        assertThat(RoadNumberAddress.parse("서울 중구 퇴계로 12-3가")).isEmpty();
        // 괄호 안 동 이름이나 뒤따르는 동 이름은 건물번호를 가리지 않는다.
        assertThat(RoadNumberAddress.parse("서울특별시 중구 세종대로 110(태평로1가)"))
                .contains(new RoadNumberAddress("서울", "세종대로", "110"));
        assertThat(RoadNumberAddress.parse("서울특별시 종로구 종로 1 종로1가"))
                .contains(new RoadNumberAddress("서울", "종로", "1"));
        assertThat(RoadNumberAddress.parse("서울특별시 중구 세종대로23길 47"))
                .contains(new RoadNumberAddress("서울", "세종대로23길", "47"));
    }
}
