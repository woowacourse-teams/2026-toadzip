package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.domain.ExternalApiCollectionFailure;

@DataJpaTest
class ExternalApiFailureStoreTest {

    @Autowired
    private ExternalApiCollectionFailureRepository repository;

    @Test
    void 외부_API_실패_조건과_원인을_저장한다() {
        Instant occurredAt = Instant.parse("2026-08-23T00:01:00Z");
        ExternalApiFailureStore store = new ExternalApiFailureStore(repository);

        store.store(ExternalApiCollectionFailure.create(
                ExternalApi.LH_LEASE_CATALOG,
                "PAGE=1&PG_SZ=100",
                occurredAt,
                "IllegalStateException",
                "외부 API 응답 오류"
        ));

        assertThat(repository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getReason()).isEqualTo("외부 API 응답 오류");
            assertThat(failure.getOccurredAt()).isEqualTo(occurredAt);
        });
    }
}
