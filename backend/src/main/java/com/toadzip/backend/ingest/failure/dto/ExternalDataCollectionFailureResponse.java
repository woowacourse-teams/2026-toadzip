package com.toadzip.backend.ingest.failure.dto;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.failure.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.time.Instant;
import java.util.UUID;

public record ExternalDataCollectionFailureResponse(
        ExternalDataSource source,
        String requestDescription,
        IngestFailureStatus status,
        int attemptCount,
        String errorType,
        String reason,
        Instant occurredAt,
        Instant lastOccurredAt,
        int occurrenceCount,
        int recurrenceCount,
        Instant lastResolvedAt,
        UUID firstExecutionId,
        UUID lastExecutionId,
        UUID lastResolvedExecutionId
) {

    public static ExternalDataCollectionFailureResponse from(
            ExternalDataCollectionFailure failure
    ) {
        return new ExternalDataCollectionFailureResponse(
                failure.getSource(),
                failure.getSourceKey(),
                failure.getStatus(),
                failure.getAttemptCount(),
                failure.getErrorType(),
                failure.getDetail(),
                failure.getOccurredAt(),
                failure.getLastOccurredAt(),
                failure.getOccurrenceCount(),
                failure.getRecurrenceCount(),
                failure.getLastResolvedAt(),
                failure.getFirstExecutionId(),
                failure.getLastExecutionId(),
                failure.getLastResolvedExecutionId()
        );
    }
}
