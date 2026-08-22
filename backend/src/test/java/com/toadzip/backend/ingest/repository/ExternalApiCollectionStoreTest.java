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
        });
    }

    @Test
    @DisplayName("같은 외부 응답을 다시 수집해도 외부 API 행을 중복 제거하지 않는다")
    void keepsRepeatedApiData() {
        ExternalApiData apiData = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "rsdtRcritNtcList?suplyTy=01&pageNo=1",
                1,
                Instant.now(),
                "{}"
        );

        store.storeApiData(List.of(apiData));
        store.storeApiData(List.of(ExternalApiData.create(
                apiData.getExternalApi(),
                apiData.getRequestDescription(),
                apiData.getPage(),
                apiData.getCollectedAt().plusSeconds(1),
                apiData.getApiData()
        )));

        assertThat(apiDataRepository.count()).isEqualTo(2);
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
