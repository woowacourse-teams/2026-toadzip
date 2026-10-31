package com.toadzip.backend.ingest.failure.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.failure.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExternalDataCollectionFailureResponseTest {

    @Test
    void 응답은_조회_조건과_오류_유형과_실패_원인을_기존_필드로_낸다() {
        Instant occurredAt = Instant.parse("2026-11-01T00:00:00Z");
        Instant resolvedAt = Instant.parse("2026-11-01T01:00:00Z");
        UUID resolvedBy = UUID.randomUUID();
        var failure = ExternalDataCollectionFailure.create(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, "panId=1", occurredAt, 2, "TimeoutException", "응답 지연");
        failure.resolve(resolvedAt, resolvedBy);

        var response = ExternalDataCollectionFailureResponse.from(failure);

        assertThat(response.source()).isEqualTo(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        assertThat(response.requestDescription()).isEqualTo("panId=1");
        assertThat(response.status()).isEqualTo(IngestFailureStatus.RESOLVED);
        assertThat(response.attemptCount()).isEqualTo(2);
        assertThat(response.errorType()).isEqualTo("TimeoutException");
        assertThat(response.reason()).isEqualTo("응답 지연");
        assertThat(response.occurredAt()).isEqualTo(occurredAt);
        assertThat(response.lastResolvedAt()).isEqualTo(resolvedAt);
        assertThat(response.lastResolvedExecutionId()).isEqualTo(resolvedBy);
    }
}
