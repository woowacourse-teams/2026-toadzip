package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.collection.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

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
        ), null);

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
        ), null);

        store.resolve(
                ExternalDataSource.MYHOME_COMPLEX,
                "pageNo=3&numOfRows=500",
                resolvedAt,
                null
        );

        assertThat(repository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.RESOLVED);
            assertThat(failure.getResolvedAt()).isEqualTo(resolvedAt);
        });
    }

    @Test
    void 대상이_아닌_요청은_스킵_상태로_변경한다() {
        Instant occurredAt = Instant.parse("2026-08-23T00:01:00Z");
        Instant skippedAt = Instant.parse("2026-08-23T00:02:00Z");
        ExternalDataFailureStore store = new ExternalDataFailureStore(repository);
        store.store(ExternalDataCollectionFailure.create(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                "myhomeAnnouncementSourceId=7",
                occurredAt,
                0,
                "IllegalStateException",
                "LH 공고 조회 조건이 없습니다."
        ), null);

        store.skip(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                "myhomeAnnouncementSourceId=7",
                skippedAt,
                "LH 공급기관이 아닌 마이홈 공고라서 수집 대상이 아닙니다.",
                null
        );

        assertThat(repository.findAll()).singleElement().satisfies(failure -> {
            assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.SKIPPED);
            assertThat(failure.getResolvedAt()).isEqualTo(skippedAt);
            assertThat(failure.getErrorType()).isEqualTo("NOT_APPLICABLE");
            assertThat(failure.getReason()).isEqualTo("LH 공급기관이 아닌 마이홈 공고라서 수집 대상이 아닙니다.");
        });
    }

    @Test
    void 이전_LH_페이지_요청의_실패는_같은_조회_조건만_해결한다() {
        Instant occurredAt = Instant.parse("2026-08-23T00:01:00Z");
        Instant resolvedAt = Instant.parse("2026-08-23T00:02:00Z");
        ExternalDataFailureStore store = new ExternalDataFailureStore(repository);
        String previousRequest = "PAN_ID=100&SPL_INF_TP_CD=060&COLLECTION_VERSION=4";
        String matchingPage = previousRequest + "&PG_SZ=100&PAGE=2";
        String otherPan = "PAN_ID=101&SPL_INF_TP_CD=060&COLLECTION_VERSION=4&PG_SZ=100&PAGE=2";
        for (String description : new String[]{previousRequest, matchingPage, otherPan}) {
            store.store(ExternalDataCollectionFailure.create(
                    ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, description, occurredAt, 1,
                    "ExternalDataRequestException", "외부 조회 실패"
            ), null);
        }

        store.resolve(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, previousRequest, resolvedAt, null);
        store.resolveStartingWith(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                previousRequest + "&PG_SZ=", resolvedAt, null);

        assertThat(repository.findAll()).filteredOn(failure ->
                failure.getRequestDescription().equals(otherPan))
                .singleElement()
                .extracting(ExternalDataCollectionFailure::getStatus)
                .isEqualTo(ExternalDataFailureStatus.PENDING);
        assertThat(repository.findAll()).filteredOn(failure ->
                !failure.getRequestDescription().equals(otherPan))
                .allMatch(failure -> failure.getStatus() == ExternalDataFailureStatus.RESOLVED);
    }
}
