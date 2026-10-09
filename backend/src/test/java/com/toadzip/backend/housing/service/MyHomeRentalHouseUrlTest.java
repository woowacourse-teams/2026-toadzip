package com.toadzip.backend.housing.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MyHomeRentalHouseUrlTest {

    @ParameterizedTest
    @CsvSource({
            "영구임대,01,C", "국민임대,02,C", "50년임대,03,C", "매입임대,04,B",
            "10년임대,05,C", "5년임대,06,C", "장기전세,07,C", "행복주택,09,C",
            "공공기숙사,10,C", "통합공공임대,11,C", "6년임대,12,C"
    })
    void 공고_API가_아닌_마이홈_웹의_공급유형_코드로_연결한다(
            String supplyType, String supplyCode, String category
    ) {
        String key = "8:3147381019:1111010100100010000" + supplyType.length() + ":" + supplyType
                + "4:55:A5:55.75-1:";

        assertThat(MyHomeRentalHouseUrl.fromSourceIdentifier(key)).isEqualTo(
                "https://www.myhome.go.kr/hws/portal/sch/selectRentalHouseInfoDetail.do"
                        + "?hsmpSn=31473810&suplyTy=" + supplyCode + "&rthousSe=" + category);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "manual-type", "8:314", "-2:x", "999999999999999:31473810",
            "8:31473810-1:4:행복주택-1:-1:",
            "8:31473810-1:4:행복주택-1:-1:-1:extra",
            "1:0-1:4:행복주택-1:-1:-1:",
            "8:31473810-1:2:기타-1:-1:-1:",
            "20:99999999999999999999-1:4:행복주택-1:-1:-1:"
    })
    void 원천키가_없거나_해석할_수_없으면_링크를_추정하지_않는다(String key) {
        assertThat(MyHomeRentalHouseUrl.fromSourceIdentifier(key)).isNull();
    }

    @Test
    void 단지_통합_후에도_각_주택형의_원래_단지_번호를_사용한다() {
        assertThat(MyHomeRentalHouseUrl.fromSourceIdentifier("8:31473810-1:4:국민임대-1:-1:-1:"))
                .contains("hsmpSn=31473810");
        assertThat(MyHomeRentalHouseUrl.fromSourceIdentifier("8:31473811-1:4:국민임대-1:-1:-1:"))
                .contains("hsmpSn=31473811");
    }
}
