package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import com.toadzip.backend.ingest.domain.ExternalDataSource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LhNoticeCollectionProgressStoreTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-25T10:00:00Z");

    @Autowired
    private LhNoticeCollectionCheckpointRepository checkpointRepository;

    @Autowired
    private LhNoticeDetailSourceRepository detailRepository;

    @Autowired
    private LhNoticeSupplySourceRepository supplyRepository;

    @Test
    void 완료한_동일_요청만_증분_수집에서_제외한다() {
        LhNoticeCollectionProgressStore store = store();
        String request = "PAN_ID=100&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06&SPL_INF_TP_CD=063";

        store.complete(ExternalDataSource.LH_NOTICE_DETAIL, "notice-100", request, "100");

        assertThat(store.isCompleted(ExternalDataSource.LH_NOTICE_DETAIL, request)).isTrue();
        assertThat(store.hasCollectionHistory(ExternalDataSource.LH_NOTICE_DETAIL, "100")).isTrue();
        assertThat(store.isCompleted(
                ExternalDataSource.LH_NOTICE_DETAIL,
                request + "&AIS_TP_CD=06"
        )).isFalse();
        assertThat(checkpointRepository.findAll()).singleElement().satisfies(checkpoint -> {
            assertThat(checkpoint.getPanId()).isEqualTo("100");
            assertThat(checkpoint.getCompletedAt()).isEqualTo(COMPLETED_AT);
        });
    }

    @Test
    void 같은_완료_요청을_다시_저장해도_체크포인트가_중복되지_않는다() {
        LhNoticeCollectionProgressStore store = store();
        String request = "PAN_ID=100&SPL_INF_TP_CD=063";

        store.complete(ExternalDataSource.LH_NOTICE_SUPPLY, "notice-100", request, "100");
        store.complete(ExternalDataSource.LH_NOTICE_SUPPLY, "another-source-key", request, "100");

        assertThat(checkpointRepository.count()).isOne();
    }

    private LhNoticeCollectionProgressStore store() {
        return new LhNoticeCollectionProgressStore(
                checkpointRepository,
                detailRepository,
                supplyRepository,
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC)
        );
    }
}
