package com.toadzip.backend.housing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HousingTypeNamesTest {

    @Test
    void 끝의_신청자_표기와_공백_대소문자_끝_형_앞자리_0은_무시한다() {
        assertThat(HousingTypeNames.key("36형 일반(고령자)")).isEqualTo(HousingTypeNames.key("36형 일반 (청년)"));
        assertThat(HousingTypeNames.key("26A[청년]")).isEqualTo(HousingTypeNames.key("26a [주거급여수급자]"));
        assertThat(HousingTypeNames.key("26A-1형[고령자(주거약자용)]")).isEqualTo("26a-1");
        assertThat(HousingTypeNames.key("055.7200A1")).isEqualTo(HousingTypeNames.key("55.7200A1"));
        assertThat(HousingTypeNames.key("26A형")).isEqualTo(HousingTypeNames.key("26A"));
    }

    @Test
    void 신청자_표기를_뗀_이름이_다르면_다른_신청형이다() {
        assertThat(HousingTypeNames.key("55A1")).isNotEqualTo(HousingTypeNames.key("55C(주거약자)"));
        assertThat(HousingTypeNames.key("26형 주거약자(고령자)")).isNotEqualTo(HousingTypeNames.key("26형(산단근로자)"));
        assertThat(HousingTypeNames.key("84.9600A")).isNotEqualTo(HousingTypeNames.key("84.9600B"));
    }

    @Test
    void 이름_전체가_괄호면_떼지_않고_빈_이름은_빈_키다() {
        assertThat(HousingTypeNames.key("(주거약자)")).isEqualTo("(주거약자)");
        assertThat(HousingTypeNames.key("  ")).isEmpty();
        assertThat(HousingTypeNames.key(null)).isEmpty();
    }

    @Test
    void 가운뎃점으로_이은_표시명은_표기마다_키를_둔다() {
        assertThat(HousingTypeNames.keys("55A1 · 55C(주거약자)")).containsExactly("55a1", "55c");
        assertThat(HousingTypeNames.keys("26A·26B")).containsExactly("26a", "26b");
    }

    @Test
    void 끝의_신청자_표기를_뗀_형명과_맨_끝_신청자_표기를_돌려준다() {
        assertThat(HousingTypeNames.withoutApplicant("26A-1형[고령자(주거약자용)]")).isEqualTo("26A-1형");
        assertThat(HousingTypeNames.withoutApplicant("55C (주거약자)")).isEqualTo("55C");
        assertThat(HousingTypeNames.applicant("36형 일반 (고령자)")).isEqualTo("고령자");
        assertThat(HousingTypeNames.applicant("26A-1형[고령자(주거약자용)]")).isEqualTo("고령자(주거약자용)");
        assertThat(HousingTypeNames.applicant("84.9600A")).isEmpty();
        assertThat(HousingTypeNames.applicant("(주거약자)")).isEqualTo("주거약자");
    }

    @Test
    void 단지_주택타입_이름은_면적_숫자로_적은_형명을_공고문_표기와_같게_본다() {
        assertThat(HousingTypeNames.typeKey("059.9100A")).isEqualTo(HousingTypeNames.typeKey("59A"));
        assertThat(HousingTypeNames.typeKey("84.9600B")).isEqualTo(HousingTypeNames.typeKey("84B형"));
        assertThat(HousingTypeNames.typeKey("055.7200A1 (주거약자)")).isEqualTo("55a1");
        assertThat(HousingTypeNames.typeKey("59B-1")).isNotEqualTo(HousingTypeNames.typeKey("59D-1"));
        // 신청형 키는 바꾸지 않는다(LH 공급행 식별자에 쓴다).
        assertThat(HousingTypeNames.key("059.9100A")).isEqualTo("59.9100a");
    }

    @Test
    void 같은_단지_주택타입_이름은_이름이_같거나_숫자_형명만_다르고_면적이_같을_때다() {
        assertThat(HousingTypeNames.sameType("59A형", null, "59a", null)).isTrue();
        assertThat(HousingTypeNames.sameType("059.9100A", new java.math.BigDecimal("59.91"), "59A", new java.math.BigDecimal("59.9100"))).isTrue();
        assertThat(HousingTypeNames.sameType("059.9300A", new java.math.BigDecimal("59.93"), "59A", new java.math.BigDecimal("59.91"))).isFalse();
        assertThat(HousingTypeNames.sameType("", null, "", null)).isFalse();
    }
}
