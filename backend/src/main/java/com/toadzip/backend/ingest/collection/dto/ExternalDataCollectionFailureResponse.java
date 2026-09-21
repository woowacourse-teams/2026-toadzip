package com.toadzip.backend.ingest.collection.dto;

import com.toadzip.backend.ingest.collection.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.collection.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import java.time.Instant;
import java.util.UUID;

public record ExternalDataCollectionFailureResponse(
        ExternalDataSource source,
        String requestDescription,
        ExternalDataFailureStatus status,
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
                failure.getRequestDescription(),
                failure.getStatus(),
                failure.getAttemptCount(),
                failure.getErrorType(),
                failure.getReason(),
                failure.getOccurredAt(),
                failure.getLastOccurredAt(),
                failure.getOccurrenceCount(),
                failure.getRecurrenceCount(),
                failure.getResolvedAt(),
                failure.getFirstExecutionId(),
                failure.getLastExecutionId(),
                failure.getResolvedExecutionId()
        );
    }
}
