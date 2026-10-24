package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LhSupplyData;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentMapper.LinkedMyHomeRow;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementSupplyMatcher.LhSupplyMatchResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LhAnnouncementSupplyMatcherTest {

    private final LhAnnouncementSupplyMatcher matcher = new LhAnnouncementSupplyMatcher();

    @ParameterizedTest
    @CsvSource({
            "중동한라1, 중동한라1 영구임대주택",
            "중동덕유1, 중동덕유1 영구임대주택"
    })
    void 마이홈과_LH의_임대주택_접미어_표기가_달라도_같은_단지로_매칭한다(
            String myHomeComplexName,
            String lhComplexName
    ) {
        SupplyRow row = row(myHomeComplexName, "26A");

        LhSupplyMatchResult result = matcher.match(List.of(row), source(lhComplexName, "26A"));

        assertThat(result.row()).isSameAs(row);
        assertThat(result.failure()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "중동한라1, 중동한라2 영구임대주택",
            "고양삼송 A-1블록, 고양삼송 A-2블록"
    })
    void 단지_번호나_블록이_다르면_매칭하지_않는다(String myHomeComplexName, String lhComplexName) {
        LhSupplyMatchResult result = matcher.match(
                List.of(row(myHomeComplexName, "26A")),
                source(lhComplexName, "26A")
        );

        assertThat(result.row()).isNull();
        assertThat(result.failure().reason())
                .isEqualTo(LhAnnouncementEnrichmentFailureReason.COMPLEX_NOT_FOUND);
    }

    @Test
    void 주택형이_다르면_같은_단지여도_매칭하지_않는다() {
        LhSupplyMatchResult result = matcher.match(
                List.of(row("중동한라1 영구임대주택", "26A")),
                source("중동한라1", "26B")
        );

        assertThat(result.row()).isNull();
        assertThat(result.failure().reason())
                .isEqualTo(LhAnnouncementEnrichmentFailureReason.HOUSING_TYPE_NOT_FOUND);
    }

    @Test
    void 마이홈과_LH의_주택형_접미어_표기가_달라도_같은_주택형으로_매칭한다() {
        SupplyRow row = row("중동한라1", "26A형");

        LhSupplyMatchResult result = matcher.match(
                List.of(row),
                source("중동한라1 영구임대주택", "26A 주택형")
        );

        assertThat(result.row()).isSameAs(row);
        assertThat(result.failure()).isNull();
    }

    @Test
    void 숫자_토큰의_경계가_다른_단지는_매칭하지_않는다() {
        LhSupplyMatchResult result = matcher.match(
                List.of(row("광명1단지 10블록", "26A")),
                source("광명110블록", "26A")
        );

        assertThat(result.row()).isNull();
        assertThat(result.failure().reason())
                .isEqualTo(LhAnnouncementEnrichmentFailureReason.COMPLEX_NOT_FOUND);
    }

    @Test
    void 단지_번호의_선행_0만_다르면_같은_단지로_매칭한다() {
        SupplyRow row = row("광명01단지", "26A");

        LhSupplyMatchResult result = matcher.match(
                List.of(row),
                source("광명1 영구임대주택", "26A")
        );

        assertThat(result.row()).isSameAs(row);
        assertThat(result.failure()).isNull();
    }

    @Test
    void 정규화한_LH_단지명이_비면_매칭하지_않는다() {
        LhSupplyMatchResult result = matcher.match(
                List.of(row("영구임대주택", "26A")),
                source("영구임대", "26A")
        );

        assertThat(result.row()).isNull();
        assertThat(result.failure().reason())
                .isEqualTo(LhAnnouncementEnrichmentFailureReason.COMPLEX_NOT_FOUND);
    }

    @Test
    void 정규화한_단지와_주택형이_같은_후보가_여러_개면_임의로_매칭하지_않는다() {
        LhSupplyMatchResult result = matcher.match(
                List.of(
                        row("중동한라1 영구임대주택", "26A"),
                        row("중동한라1", "26A")
                ),
                source("중동한라1", "26A")
        );

        assertThat(result.row()).isNull();
        assertThat(result.failure().reason())
                .isEqualTo(LhAnnouncementEnrichmentFailureReason.AMBIGUOUS_HOUSING_TYPE);
    }

    // 로컬 DB #45 아산읍내: 정제가 LH 행으로 나눈 공급행인데 단지명 표기가 셋 다 달라 보강이 거절됐다.
    @Test
    void 정제가_붙인_LH_원천_식별자가_같으면_단지명_표기가_달라도_매칭한다() {
        SupplyRow row = row("아산읍내", "26");
        when(row.getLhSourceSupplyRowIdentifier()).thenReturn("lh-supply-row");

        LhSupplyMatchResult result = matcher.match(List.of(row), source("아산읍내1 영구임대아파트", "26"));

        assertThat(result.row()).isSameAs(row);
        assertThat(result.failure()).isNull();
    }

    @Test
    void LH_원천_식별자가_같아도_주택형이_다르면_매칭하지_않는다() {
        SupplyRow row = row("아산읍내", "26");
        when(row.getLhSourceSupplyRowIdentifier()).thenReturn("lh-supply-row");

        LhSupplyMatchResult result = matcher.match(List.of(row), source("아산읍내1 영구임대아파트", "31"));

        assertThat(result.row()).isNull();
    }

    @Test
    void 저장한_연결이_있으면_단지명이_달라도_그_마이홈_원천행의_공급행과_매칭한다() {
        SupplyRow other = row("전주평화4 영구임대주택", "26");
        SupplyRow linked = row("평화주공4단지", "26");
        when(linked.getMyhomeSourceRowIdentifier()).thenReturn("myhome-row");
        var source = source("전주평화4 영구임대주택", "26").withLinkedRow(new LinkedMyHomeRow("myhome-row", false));

        LhSupplyMatchResult result = matcher.match(List.of(other, linked), source);

        assertThat(result.row()).isSameAs(linked);
    }

    @Test
    void 원천이_바뀐_저장_연결은_자동_매칭하지_않고_재확인을_요구한다() {
        SupplyRow row = row("전주평화4 영구임대주택", "26");
        when(row.getLhSourceSupplyRowIdentifier()).thenReturn("lh-supply-row");
        var source = source("전주평화4 영구임대주택", "26").withLinkedRow(new LinkedMyHomeRow("myhome-row", true));

        LhSupplyMatchResult result = matcher.match(List.of(row), source);

        assertThat(result.row()).isNull();
        assertThat(result.failure().reason())
                .isEqualTo(LhAnnouncementEnrichmentFailureReason.LH_SUPPLY_LINK_REVIEW_REQUIRED);
    }

    @Test
    void 실패한_행의_비교_후보는_단지명이_다르면_같은_주택형_주택형이_다르면_같은_단지의_공급행이다() {
        SupplyRow sameType = row("대전중촌2", "19A(주거약자용)");
        SupplyRow sameComplex = row("대전관저5", "26");
        SupplyRow other = row("대전산내", "39");

        var complexNotFound = matcher.candidates(List.of(sameType, sameComplex, other),
                source("대전중촌2 증축 영구임대주택(주거복지동)", "19A(주거약자용)"),
                LhAnnouncementEnrichmentFailureReason.COMPLEX_NOT_FOUND);
        var typeNotFound = matcher.candidates(List.of(sameType, sameComplex, other),
                source("대전관저5 영구임대주택", "26D"), LhAnnouncementEnrichmentFailureReason.HOUSING_TYPE_NOT_FOUND);

        assertThat(complexNotFound).containsExactly(sameType);
        assertThat(typeNotFound).containsExactly(sameComplex);
    }

    private SupplyRow row(String complexName, String housingTypeName) {
        SupplyRow row = mock(SupplyRow.class);
        when(row.getSourceComplexName()).thenReturn(complexName);
        when(row.getSourceHousingTypeName()).thenReturn(housingTypeName);
        return row;
    }

    private LhSupplyData source(String complexName, String housingTypeName) {
        return new LhSupplyData(
                "lh-supply-row", complexName, housingTypeName, null, null, null, null, null
        );
    }
}
