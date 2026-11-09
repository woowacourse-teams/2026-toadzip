package com.toadzip.backend.ingest.failure.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExternalDataCollectionFailureTest {

    private static final Instant FIRST = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant SKIPPED = Instant.parse("2026-11-01T01:00:00Z");
    private static final Instant AGAIN = Instant.parse("2026-11-01T02:00:00Z");

    @Test
    void 건너뛴_실패가_다시_발생하면_재발로_세고_최신_오류로_바꾼다() {
        var failure = ExternalDataCollectionFailure.create(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, "panId=1", FIRST, 1, "TimeoutException", "응답 지연");
        UUID skippedBy = UUID.randomUUID();
        failure.skip(SKIPPED, "수집 대상이 아닙니다.", skippedBy);

        assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.SKIPPED);
        assertThat(failure.getErrorType()).isEqualTo("NOT_APPLICABLE");
        assertThat(failure.getReason()).isEqualTo("수집 대상이 아닙니다.");
        assertThat(failure.getResolvedAt()).isEqualTo(SKIPPED);
        assertThat(failure.getResolvedExecutionId()).isEqualTo(skippedBy);

        UUID observedBy = UUID.randomUUID();
        failure.observe(ExternalDataCollectionFailure.create(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, "panId=1", AGAIN, 3, "IOException", "연결 끊김"), observedBy);

        assertThat(failure.getStatus()).isEqualTo(ExternalDataFailureStatus.PENDING);
        assertThat(failure.getRecurrenceCount()).isOne();
        assertThat(failure.getOccurrenceCount()).isEqualTo(2);
        assertThat(failure.getLastOccurredAt()).isEqualTo(AGAIN);
        assertThat(failure.getAttemptCount()).isEqualTo(3);
        assertThat(failure.getErrorType()).isEqualTo("IOException");
        assertThat(failure.getReason()).isEqualTo("연결 끊김");
        assertThat(failure.getLastExecutionId()).isEqualTo(observedBy);
    }
}
