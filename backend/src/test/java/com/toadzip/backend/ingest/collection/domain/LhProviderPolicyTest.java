package com.toadzip.backend.ingest.collection.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LhProviderPolicyTest {

    @Test
    void LH_지역기관과_법인명이_LH로_판정된다() {
        assertThat(LhProviderPolicy.isLh("LH")).isTrue();
        assertThat(LhProviderPolicy.isLh(" LH서울 ")).isTrue();
        assertThat(LhProviderPolicy.isLh("한국토지주택공사")).isTrue();
    }

    @Test
    void 다른_공급기관과_빈_값은_LH가_아니다() {
        assertThat(LhProviderPolicy.isLh("서울주택도시공사")).isFalse();
        assertThat(LhProviderPolicy.isLh(null)).isFalse();
        assertThat(LhProviderPolicy.isLh(" ")).isFalse();
    }
}
