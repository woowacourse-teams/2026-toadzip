package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LhHousingTypeHouseholdMatcherTest {

    private final LhHousingTypeHouseholdMatcher matcher = new LhHousingTypeHouseholdMatcher();

    @Test
    void LH_이름이_마이홈_이름의_일부이면_매칭한다() {
        HousingComplex complex = complex("강릉송정주공아파트", "NATIONAL_RENTAL", 623);
        LhHousingTypeHouseholdSource source = source("강릉송정", "국민임대", 623);

        assertThat(matcher.findMatches(List.of(complex), source)).containsExactly(complex);
    }

    @Test
    void 출처별_장식어를_제외한_이름과_동일한_동호수이면_매칭한다() {
        HousingComplex complex = complex("샘터마을2단지", "NATIONAL_RENTAL", 504);
        LhHousingTypeHouseholdSource source = source("능곡샘터2", "국민임대", 504);

        assertThat(matcher.findMatches(List.of(complex), source)).containsExactly(complex);
    }

    @Test
    void 구조_조건이_하나여도_이름이_무관하면_매칭하지_않는다() {
        HousingComplex complex = complex("운암주공6단지아파트", "PUBLIC_RENTAL_50Y", 571);
        LhHousingTypeHouseholdSource source = source(
                "공공임대50년 수도권 테스트 단지", "공공임대", 571
        );

        assertThat(matcher.findMatches(List.of(complex), source)).isEmpty();
    }

    @Test
    void 단지_번호가_다르면_이름이_유사해도_매칭하지_않는다() {
        HousingComplex complex = complex("문산선유2단지", "NATIONAL_RENTAL", 504);
        LhHousingTypeHouseholdSource source = source("문산선유3단지", "국민임대", 504);

        assertThat(matcher.findMatches(List.of(complex), source)).isEmpty();
    }

    @Test
    void 부분_일치_후보가_여러_개이면_이름_유사도가_가장_높은_후보만_선택한다() {
        HousingComplex expected = complex("강릉송정주공아파트", "NATIONAL_RENTAL", 623);
        HousingComplex other = complex("강릉송정타운아파트", "NATIONAL_RENTAL", 623);
        LhHousingTypeHouseholdSource source = source("강릉송정주공", "국민임대", 623);

        assertThat(matcher.findMatches(List.of(other, expected), source)).containsExactly(expected);
    }

    private LhHousingTypeHouseholdSource source(
            String name,
            String supplyType,
            int householdCount
    ) {
        return new LhHousingTypeHouseholdSource(
                "강원특별자치도 강릉시",
                supplyType,
                name,
                householdCount,
                List.of()
        );
    }

    private HousingComplex complex(String name, String supplyType, int householdCount) {
        Address address = Address.create(
                "강원특별자치도 강릉시 테스트로 1",
                "4215010100100010000",
                "4215010100",
                "42",
                "42150",
                new BigDecimal("37.751853"),
                new BigDecimal("128.876057")
        );
        return HousingComplex.createFromMyHome(
                name,
                name + ":" + supplyType,
                supplyType,
                address,
                householdCount,
                "LH",
                null,
                "DISTRICT",
                "APARTMENT",
                "CORRIDOR",
                true,
                100
        );
    }
}
