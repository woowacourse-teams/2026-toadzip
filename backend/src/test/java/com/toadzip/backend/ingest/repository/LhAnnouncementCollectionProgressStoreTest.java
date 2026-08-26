package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.LhAnnouncementDetailSource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LhAnnouncementCollectionProgressStoreTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-25T10:00:00Z");

    @Autowired
    private LhAnnouncementCollectionCheckpointRepository checkpointRepository;

    @Autowired
    private LhAnnouncementDetailSourceRepository detailRepository;

    @Autowired
    private LhAnnouncementSupplySourceRepository supplyRepository;

    @Test
    void 완료한_동일_요청만_증분_수집에서_제외한다() {
        LhAnnouncementCollectionProgressStore store = store();
        String request = "PAN_ID=100&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06&SPL_INF_TP_CD=063";

        store.complete(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, "announcement-100", request, "100");

        var progress = store.findBatch(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                List.of(request, request + "&AIS_TP_CD=06"),
                List.of("100")
        );

        assertThat(progress.isCompleted(request)).isTrue();
        assertThat(progress.historyPanIds()).containsExactly("100");
        assertThat(progress.isCompleted(request + "&AIS_TP_CD=06")).isFalse();
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
    }

    @Test
    void 배치의_완료_요청과_적재_panId와_수집_이력을_한번에_조회한다() {
        LhAnnouncementCollectionProgressStore store = store();
        String completedRequest = "PAN_ID=100&SPL_INF_TP_CD=063";
        String historyRequest = "PAN_ID=200&SPL_INF_TP_CD=063";
        store.complete(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, "announcement-100", completedRequest, "100");
        store.complete(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, "announcement-200", historyRequest, "200");
        detailRepository.save(new LhAnnouncementDetailSource(
                0, "100", "ETC_INFO", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null
        ));

        var progress = store.findBatch(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                List.of(completedRequest, "PAN_ID=300&SPL_INF_TP_CD=063"),
                List.of("100", "200", "300")
        );

        assertThat(progress.isCompleted(completedRequest)).isTrue();
        assertThat(progress.storedPanIds()).containsExactly("100");
        assertThat(progress.historyPanIds()).containsExactlyInAnyOrder("100", "200");
    }

    private LhAnnouncementCollectionProgressStore store() {
        return new LhAnnouncementCollectionProgressStore(
                checkpointRepository,
                detailRepository,
                supplyRepository,
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC)
        );
    }
}
