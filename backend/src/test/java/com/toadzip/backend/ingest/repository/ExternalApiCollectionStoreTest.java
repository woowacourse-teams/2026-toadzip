package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.toadzip.backend.ingest.domain.ExternalApiCollectionFailure;
import com.toadzip.backend.ingest.domain.ExternalApiData;
import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.domain.LhNoticeProcessingStatus;

@DataJpaTest
class ExternalApiCollectionStoreTest {

    @Autowired
    private ExternalApiDataRepository apiDataRepository;

    @Autowired
    private ExternalApiCollectionFailureRepository failureRepository;

    private ExternalApiCollectionStore store;

    @BeforeEach
    void setUp() {
        store = new ExternalApiCollectionStore(apiDataRepository, failureRepository);
    }

    @Test
    @DisplayName("API 데이터와 수집 시각을 변경 없이 저장한다")
    void storesApiDataAndCollectedAt() {
        Instant collectedAt = Instant.parse("2026-08-23T00:00:00Z");
        String payload = "{\"response\": { \"body\": { \"item\": [] } }}";

        store.storeApiData(List.of(ExternalApiData.create(
                ExternalApi.MYHOME_COMPLEX,
                "rentalHouseGwList?brtcCode=11&pageNo=1",
                1,
                collectedAt,
                payload
        )));

        assertThat(apiDataRepository.findAll()).singleElement().satisfies(apiData -> {
            assertThat(apiData.getApiData()).isEqualTo(payload);
            assertThat(apiData.getCollectedAt()).isEqualTo(collectedAt);
            assertThat(apiData.getExternalApi()).isEqualTo(ExternalApi.MYHOME_COMPLEX);
            assertThat(apiData.getContentHash()).hasSize(64);
        });
        assertThat(apiDataRepository.existsByExternalApiAndRequestDescriptionIn(
                ExternalApi.MYHOME_COMPLEX,
                List.of("rentalHouseGwList?brtcCode=11&pageNo=1")
        )).isTrue();
        assertThat(apiDataRepository.existsByExternalApiAndRequestDescriptionIn(
                ExternalApi.MYHOME_COMPLEX,
                List.of("rentalHouseGwList?brtcCode=11&pageNo=2")
        )).isFalse();
    }

    @Test
    void 실제로_호출한_동일한_원본_응답도_각각_snapshot으로_저장한다() {
        ExternalApiData apiData = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "rsdtRcritNtcList?suplyTy=01&pageNo=1",
                1,
                Instant.now(),
                "{}"
        );

        int firstStoredCount = store.storeApiData(List.of(apiData));
        int secondStoredCount = store.storeApiData(List.of(ExternalApiData.create(
                apiData.getExternalApi(),
                apiData.getRequestDescription(),
                apiData.getPage(),
                apiData.getCollectedAt().plusSeconds(1),
                apiData.getApiData()
        )));

        assertThat(firstStoredCount).isOne();
        assertThat(secondStoredCount).isOne();
        assertThat(apiDataRepository.count()).isEqualTo(2);
    }

    @Test
    void 같은_API와_조회_조건이어도_응답_내용이_변경되면_새_snapshot으로_저장한다() {
        Instant collectedAt = Instant.parse("2026-08-23T00:00:00Z");
        String requestDescription = "rsdtRcritNtcList?suplyTy=01&pageNo=1";

        store.storeApiData(List.of(ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                requestDescription,
                1,
                collectedAt,
                "{\"noticeId\":\"original\"}"
        )));
        int storedCount = store.storeApiData(List.of(ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                requestDescription,
                1,
                collectedAt.plusSeconds(60),
                "{\"noticeId\":\"correction\"}"
        )));

        assertThat(storedCount).isOne();
        assertThat(apiDataRepository.count()).isEqualTo(2);
    }

    @Test
    void LH_공고_수집이_완료된_마이홈_snapshot은_미처리_조회에서_제외한다() {
        ExternalApiData sourceApiData = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "rsdtRcritNtcList?suplyTy=01&pageNo=1",
                1,
                Instant.parse("2026-08-23T00:00:00Z"),
                "{}"
        );
        store.storeApiData(List.of(sourceApiData));

        assertThat(apiDataRepository
                .findAllPendingLhNoticeApiData(
                        ExternalApi.MYHOME_NOTICE,
                        LhNoticeProcessingStatus.PENDING
                )).hasSize(1);

        store.completeLhNoticeProcessing(sourceApiData, Instant.parse("2026-08-23T00:01:00Z"));

        assertThat(apiDataRepository
                .findAllPendingLhNoticeApiData(
                        ExternalApi.MYHOME_NOTICE,
                        LhNoticeProcessingStatus.PENDING
                )).isEmpty();
        assertThat(sourceApiData.getLhNoticeProcessingStatus())
                .isEqualTo(LhNoticeProcessingStatus.COMPLETED);
        assertThat(sourceApiData.getLhNoticeProcessedAt())
                .isEqualTo(Instant.parse("2026-08-23T00:01:00Z"));
    }

    @Test
    void 처리할_수_없는_마이홈_snapshot은_실패_상태로_보존한다() {
        ExternalApiData sourceApiData = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "rsdtRcritNtcList?suplyTy=01&pageNo=1",
                1,
                Instant.parse("2026-08-23T00:00:00Z"),
                "{}"
        );
        store.storeApiData(List.of(sourceApiData));

        store.failLhNoticeProcessing(sourceApiData, Instant.parse("2026-08-23T00:01:00Z"));

        assertThat(sourceApiData.getLhNoticeProcessingStatus())
                .isEqualTo(LhNoticeProcessingStatus.FAILED);
    }

    @Test
    @DisplayName("외부 조회 실패 원인과 시각을 저장한다")
    void storesFailureReasonAndOccurredAt() {
        Instant occurredAt = Instant.parse("2026-08-23T00:01:00Z");
        store.storeFailure(ExternalApiCollectionFailure.create(
                ExternalApi.LH_LEASE_CATALOG,
                "lhLeaseInfo1/lhLeaseInfo1?page=1",
                occurredAt,
                "IllegalStateException",
                "외부 API 응답 오류"
        ));

        assertThat(failureRepository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getReason()).isEqualTo("외부 API 응답 오류");
            assertThat(failure.getOccurredAt()).isEqualTo(occurredAt);
        });
    }
}
