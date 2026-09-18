package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
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
