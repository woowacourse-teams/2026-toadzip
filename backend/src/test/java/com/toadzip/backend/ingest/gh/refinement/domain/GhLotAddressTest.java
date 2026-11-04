package com.toadzip.backend.ingest.gh.refinement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GhLotAddressTest {

    @Test
    void 지번_뒤에_붙은_단지명이나_괄호_도로명은_버리고_시군구와_본번_부번을_읽는다() {
        assertThat(GhLotAddress.parse("경기도 남양주시 다산동 6107 다산 센트럴파크"))
                .contains(new GhLotAddress("남양주시", false, 6107, 0));
        assertThat(GhLotAddress.parse("경기도 안양시 동안구 관양동 1498-21 안양관양 행복주택"))
                .contains(new GhLotAddress("안양시", false, 1498, 21));
        assertThat(GhLotAddress.parse("경기도 평택시 이충동 395 (경기도 평택시 정암로 54번길 21-12)"))
                .contains(new GhLotAddress("평택시", false, 395, 0));
        assertThat(GhLotAddress.parse("경기도 안양시 만안구 안양7동 190-11"))
                .contains(new GhLotAddress("안양시", false, 190, 11));
    }

    @Test
    void 산_지번은_산으로_구분한다() {
        assertThat(GhLotAddress.parse("경기도 광주시 송정동 산 12-3")).contains(new GhLotAddress("광주시", true, 12, 3));
        assertThat(GhLotAddress.parse("경기도 광주시 송정동 산12-3")).contains(new GhLotAddress("광주시", true, 12, 3));
    }

    @Test
    void 여러_필지나_블록이라_한_지번으로_정할_수_없는_소재지는_읽지_않는다() {
        assertThat(GhLotAddress.parse("경기도 하남시 덕풍동 656-6 일원")).isEmpty();
        assertThat(GhLotAddress.parse("경기도 하남시 덕풍동 656-6 외 3필지")).isEmpty();
        assertThat(GhLotAddress.parse("경기도 남양주시 진건읍 다산지금 A3블록")).isEmpty();
        assertThat(GhLotAddress.parse("경기도 남양주시")).isEmpty();
        assertThat(GhLotAddress.parse(null)).isEmpty();
    }

    @Test
    void PNU의_산_여부와_본번_부번이_같아야_같은_지번이다() {
        var address = new GhLotAddress("남양주시", false, 6107, 0);

        assertThat(address.matchesPnu("4136010100161070000")).isTrue();
        assertThat(address.matchesPnu("4136010100261070000")).isFalse();
        assertThat(address.matchesPnu("4136010100161070001")).isFalse();
        assertThat(address.matchesPnu("41360101")).isFalse();
        assertThat(address.matchesPnu(null)).isFalse();
    }
}
