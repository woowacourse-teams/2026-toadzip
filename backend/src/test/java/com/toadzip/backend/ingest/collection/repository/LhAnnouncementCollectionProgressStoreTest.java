package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LhAnnouncementCollectionProgressStoreTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-25T10:00:00Z");

    @Autowired
    private LhAnnouncementCollectionCheckpointRepository checkpointRepository;

    @Autowired
    private LhAnnouncementCollectionLinkRepository linkRepository;

    @Test
    void 완료한_동일_요청만_증분_수집에서_제외한다() {
        LhAnnouncementCollectionProgressStore store = store();
        String request = "PAN_ID=100&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06&SPL_INF_TP_CD=063";

        store.complete(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, "announcement-100", request, "100");

        var progress = store.findBatch(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                List.of(request, request + "&AIS_TP_CD=06"),
                List.of(),
                COMPLETED_AT.minusSeconds(1)
        );

        assertThat(progress.isFresh(request)).isTrue();
        assertThat(progress.isFresh(request + "&AIS_TP_CD=06")).isFalse();
        assertThat(checkpointRepository.findAll()).singleElement().satisfies(checkpoint -> {
            assertThat(checkpoint.getPanId()).isEqualTo("100");
            assertThat(checkpoint.getCompletedAt()).isEqualTo(COMPLETED_AT);
        });
    }

    @Test
    void 같은_완료_요청을_다시_저장해도_체크포인트가_중복되지_않는다() {
        LhAnnouncementCollectionProgressStore store = store();
        String request = "PAN_ID=100&SPL_INF_TP_CD=063";

        store.complete(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, "announcement-100", request, "100");
        store.complete(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, "another-source-key", request, "100");

        assertThat(checkpointRepository.count()).isOne();
        assertThat(linkRepository.count()).isEqualTo(2);
    }

    @Test
    void 같은_요청의_재수집이_성공하면_체크포인트_완료_시각을_갱신한다() {
        String request = "PAN_ID=100&SPL_INF_TP_CD=063";
        store().complete(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                "announcement-100",
                request,
                "100"
        );
        Instant refreshedAt = COMPLETED_AT.plusSeconds(60);
        store(refreshedAt).complete(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                "announcement-100",
                request,
                "100"
        );

        assertThat(checkpointRepository.findAll()).singleElement().satisfies(checkpoint ->
                assertThat(checkpoint.getCompletedAt()).isEqualTo(refreshedAt)
        );
    }

    @Test
    void 신선한_요청을_다른_공고에_연결해도_체크포인트_완료_시각은_유지한다() {
        String request = "PAN_ID=100&SPL_INF_TP_CD=063";
        store().complete(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                "announcement-100",
                request,
                "100"
        );
        Instant linkedAt = COMPLETED_AT.plusSeconds(60);
        store(linkedAt).link(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                "announcement-101",
                request,
                "100"
        );

        assertThat(checkpointRepository.findAll()).singleElement().satisfies(checkpoint ->
                assertThat(checkpoint.getCompletedAt()).isEqualTo(COMPLETED_AT)
        );
        assertThat(linkRepository.findAll()).hasSize(2);
    }

    @Test
    void 완료_시각이_신선도_경계와_같으면_만료된_요청이다() {
        String request = "PAN_ID=100&SPL_INF_TP_CD=063";
        store().complete(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                "announcement-100",
                request,
                "100"
        );

        var progress = store().findBatch(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                List.of(request),
                List.of("announcement-100"),
                COMPLETED_AT
        );

        assertThat(progress.isFresh(request)).isFalse();
    }

    @Test
    void 요청을_공유하는_공고별_연결을_배치_상태에서_구분한다() {
        LhAnnouncementCollectionProgressStore store = store();
        String request = "PAN_ID=100&SPL_INF_TP_CD=063";
        store.complete(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, "announcement-100", request, "100");
        store.complete(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, "announcement-101", request, "100");

        var progress = store.findBatch(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                List.of(request),
                List.of("announcement-100", "announcement-101"),
                COMPLETED_AT.minusSeconds(1)
        );

        assertThat(progress.isFresh(request)).isTrue();
        assertThat(progress.isLinkedTo("announcement-100", request)).isTrue();
        assertThat(progress.isLinkedTo("announcement-101", request)).isTrue();
    }

    @Test
    void 공고_링크가_현재_요청과_다르면_완료된_연결로_판정하지_않는다() {
        LhAnnouncementCollectionProgressStore store = store();
        String previousRequest = "PAN_ID=100&SPL_INF_TP_CD=063";
        String currentRequest = "PAN_ID=200&SPL_INF_TP_CD=063";
        store.complete(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                "announcement-100",
                previousRequest,
                "100"
        );

        var progress = store.findBatch(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                List.of(currentRequest),
                List.of("announcement-100"),
                COMPLETED_AT.minusSeconds(1)
        );

        assertThat(progress.isLinkedTo("announcement-100", currentRequest)).isFalse();
    }

    private LhAnnouncementCollectionProgressStore store() {
        return store(COMPLETED_AT);
    }

    private LhAnnouncementCollectionProgressStore store(Instant completedAt) {
        return new LhAnnouncementCollectionProgressStore(
                checkpointRepository,
                linkRepository,
                Clock.fixed(completedAt, ZoneOffset.UTC)
        );
    }
}
