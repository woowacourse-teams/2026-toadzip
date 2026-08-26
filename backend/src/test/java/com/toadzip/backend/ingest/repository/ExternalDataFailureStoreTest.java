package com.toadzip.backend.ingest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.domain.ExternalDataFailureStatus;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ExternalDataFailureStoreTest {

    @Autowired
    private ExternalDataCollectionFailureRepository repository;

    @Test
    void 외부_API_실패_조건과_원인을_저장한다() {
        Instant occurredAt = Instant.parse("2026-08-23T00:01:00Z");
        ExternalDataFailureStore store = new ExternalDataFailureStore(repository);

        store.store(ExternalDataCollectionFailure.create(
                ExternalDataSource.LH_LEASE_CATALOG,
                "PAGE=1&PG_SZ=100",
                occurredAt,
                3,
                "IllegalStateException",
                "외부 API 응답 오류"
        ));

        assertThat(repository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getReason()).isEqualTo("외부 API 응답 오류");
            assertThat(failure.getOccurredAt()).isEqualTo(occurredAt);
            assertThat(failure.getAttemptCount()).isEqualTo(3);
            assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.PENDING);
        });
    }

    @Test
    void 같은_요청이_성공하면_기존_실패를_해결_상태로_변경한다() {
        Instant occurredAt = Instant.parse("2026-08-23T00:01:00Z");
        Instant resolvedAt = Instant.parse("2026-08-23T00:02:00Z");
        ExternalDataFailureStore store = new ExternalDataFailureStore(repository);
        store.store(ExternalDataCollectionFailure.create(
                ExternalDataSource.MYHOME_COMPLEX,
                "pageNo=3&numOfRows=500",
                occurredAt,
                3,
                "ExternalDataRequestException",
                "resultCode=05"
        ));

        store.resolve(ExternalDataSource.MYHOME_COMPLEX, "pageNo=3&numOfRows=500", resolvedAt);

        assertThat(repository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.RESOLVED);
            assertThat(failure.getResolvedAt()).isEqualTo(resolvedAt);
        });
    }
}
