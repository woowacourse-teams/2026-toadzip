package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCatalogSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCatalogSourceRepository;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Skipped;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LhAnnouncementCollectionCandidateResolverTest {

    private final LhAnnouncementCatalogSourceRepository catalogRepository =
            mock(LhAnnouncementCatalogSourceRepository.class);
    private final LhAnnouncementCollectionCandidateResolver resolver =
            new LhAnnouncementCollectionCandidateResolver(new LhSupplyInfoTypeCodeResolver(), catalogRepository);

    @Test
    void LH_공고의_공급유형과_URL로_수집_후보를_만든다() {
        MyHomeAnnouncementSource source = source(
                "LH",
                "행복주택",
                "https://apply.lh.or.kr/panDetail?panId=100"
                        + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=48"
        );

        var resolution = resolver.resolve(source);

        assertThat(resolution).isInstanceOfSatisfying(Candidate.class, candidate -> {
            assertThat(candidate.sourceAnnouncementKey()).isEqualTo("100");
            assertThat(candidate.panId()).isEqualTo("100");
            assertThat(candidate.request().supplyInfoTypeCode()).isEqualTo("063");
        });
    }

    @Test
    void LH가_아닌_공고는_건너뛸_대상으로_분류한다() {
        MyHomeAnnouncementSource source = source(
                "서울주택도시공사",
                "행복주택",
                "https://apply.lh.or.kr/panDetail?panId=100"
                        + "&ccrCnntSysDsCd=03&uppAisTpCd=06"
        );

        var resolution = resolver.resolve(source);

        assertThat(resolution).isInstanceOfSatisfying(Skipped.class, skipped ->
                assertThat(skipped.reason()).isEqualTo(
                        "LH 공급기관이 아닌 마이홈 공고라서 수집 대상이 아닙니다."
                ));
    }

    @Test
    void 목록에서_유일하게_연결되면_공식_공급코드와_변경시각을_사용한다() {
        Instant observedAt = Instant.parse("2026-09-25T00:00:00Z");
        var row = LhAnnouncementCatalogSource.from(new LhAnnouncementCatalogSnapshot(
                "100", "03", "06", "48", "064", "공고", "공고중", "", "", "", "", ""
        ), "{}", observedAt);
        when(catalogRepository.findAllByPanIdInAndPresentInLatestCatalogTrue(List.of("100")))
                .thenReturn(List.of(row));
        var source = source("LH", "행복주택",
                "https://apply.lh.or.kr/panDetail?panId=100&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=48");

        var resolutions = resolver.resolveAll(List.of(source, source));

        assertThat(resolutions).allSatisfy(resolution ->
                assertThat(resolution).isInstanceOfSatisfying(Candidate.class, candidate -> {
                    assertThat(candidate.request().supplyInfoTypeCode()).isEqualTo("064");
                    assertThat(candidate.catalogChangedAt()).isEqualTo(observedAt);
                    assertThat(candidate.catalogCollectedAt()).isEqualTo(observedAt);
                }));
        verify(catalogRepository).findAllByPanIdInAndPresentInLatestCatalogTrue(List.of("100"));
    }

    @Test
    void 같은_공고ID여도_유형이_다르면_기존_마이홈_조회조건을_유지한다() {
        var row = LhAnnouncementCatalogSource.from(new LhAnnouncementCatalogSnapshot(
                "100", "03", "06", "10", "061", "공고", "공고중", "", "", "", "", ""
        ), "{}", Instant.now());
        when(catalogRepository.findAllByPanIdInAndPresentInLatestCatalogTrue(List.of("100")))
                .thenReturn(List.of(row));

        var resolution = resolver.resolve(source("LH", "행복주택",
                "https://apply.lh.or.kr/panDetail?panId=100&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=48"));

        assertThat(resolution).isInstanceOfSatisfying(Candidate.class, candidate -> {
            assertThat(candidate.request().supplyInfoTypeCode()).isEqualTo("063");
            assertThat(candidate.catalogCollectedAt()).isNull();
        });
    }

    private MyHomeAnnouncementSource source(String provider, String supplyType, String url) {
        MyHomeAnnouncementSourceSnapshot snapshot = new MyHomeAnnouncementSourceSnapshot(
                "100", 1, null, "공고", provider, null, supplyType, null, null, null,
                null, null, null, url, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
        return MyHomeAnnouncementSource.from(0, snapshot);
    }
}
