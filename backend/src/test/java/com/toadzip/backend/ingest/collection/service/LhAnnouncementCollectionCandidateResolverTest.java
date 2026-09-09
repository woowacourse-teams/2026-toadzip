package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementSourceItem;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.service.LhAnnouncementCollectionCandidateResolver.Skipped;
import org.junit.jupiter.api.Test;

class LhAnnouncementCollectionCandidateResolverTest {

    private final LhAnnouncementCollectionCandidateResolver resolver =
            new LhAnnouncementCollectionCandidateResolver(new LhSupplyInfoTypeCodeResolver());

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

    private MyHomeAnnouncementSource source(String provider, String supplyType, String url) {
        MyHomeAnnouncementSourceItem item = new MyHomeAnnouncementSourceItem(
                "100", 1, null, "공고", provider, null, supplyType, null, null, null,
                null, null, null, url, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
        return MyHomeAnnouncementSource.from(0, item.toSourceData());
    }
}
