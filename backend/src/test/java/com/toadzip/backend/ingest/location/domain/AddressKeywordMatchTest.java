package com.toadzip.backend.ingest.location.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AddressKeywordMatchTest {

    private static final JusoAddress CITY_HALL = new JusoAddress("서울특별시 중구 세종대로 110 (태평로1가)",
            "서울특별시 중구 태평로1가 31 서울특별시청", "1114010300", "111403101001", false, 110, 0, false, 31, 0);
    private static final JusoAddress MOUNTAIN_LOT = new JusoAddress("", "강원특별자치도 평창군 용평면 장평리 산 12-3",
            "5176033021", "", false, 0, 0, true, 12, 3);

    @ParameterizedTest
    @ValueSource(strings = {"서울특별시 중구 세종대로 110", "서울시 중구 세종대로 110", "서울 중구 세종대로110",
            "서울특별시 중구 태평로1가 31", "서울시 중구 태평로1가 31번지"})
    void 시도_약칭과_띄어쓰기와_괄호_참고항목이_달라도_같은_도로명주소나_지번이면_같은_곳이다(String keyword) {
        assertThat(AddressKeywordMatch.matches(keyword, CITY_HALL)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"서울특별시 중구 세종대로 11", "서울특별시 중구 세종대로 110-1", "부산광역시 중구 세종대로 110",
            "중구 세종대로 110", "서울특별시 중구 태평로1가 3", "서울특별시 중구 태평로2가 31", "서울특별시 중구"})
    void 번호나_시도나_동이_다르거나_시도가_없으면_같은_곳으로_보지_않는다(String keyword) {
        assertThat(AddressKeywordMatch.matches(keyword, CITY_HALL)).isFalse();
    }

    @Test
    void 산_번지는_산_여부까지_같아야_같은_곳이다() {
        assertThat(AddressKeywordMatch.matches("강원도 평창군 용평면 장평리 산12-3", MOUNTAIN_LOT)).isTrue();
        assertThat(AddressKeywordMatch.matches("강원특별자치도 평창군 용평면 장평리 12-3", MOUNTAIN_LOT)).isFalse();
    }
}
