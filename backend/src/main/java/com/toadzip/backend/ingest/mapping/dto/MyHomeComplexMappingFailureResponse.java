package com.toadzip.backend.ingest.mapping.dto;

import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.time.Instant;
import java.util.UUID;

public record MyHomeComplexMappingFailureResponse(
        String sourceKey,
        String sourceComplexIdentifier,
        MyHomeComplexMappingFailureReason reason,
        String detail,
        Instant occurredAt,
        Instant lastOccurredAt,
        int occurrenceCount,
        int recurrenceCount,
        IngestFailureStatus status,
        Instant lastResolvedAt,
        UUID firstExecutionId,
        UUID lastExecutionId,
        UUID lastResolvedExecutionId
) {

    public MyHomeComplexMappingFailureResponse(
            String sourceKey,
            String sourceComplexIdentifier,
            MyHomeComplexMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        this(
                sourceKey, sourceComplexIdentifier, reason, detail,
                occurredAt, occurredAt, 1, 0,
                IngestFailureStatus.PENDING, null, null, null, null
        );
    }

    public static MyHomeComplexMappingFailureResponse from(MyHomeComplexMappingFailure failure) {
        return new MyHomeComplexMappingFailureResponse(
                failure.getSourceKey(),
                failure.getSourceComplexIdentifier(),
                failure.getReason(),
                failure.getDetail(),
                failure.getOccurredAt(),
                failure.getLastOccurredAt(),
                failure.getOccurrenceCount(),
                failure.getRecurrenceCount(),
                failure.getStatus(),
                failure.getLastResolvedAt(),
                failure.getFirstExecutionId(),
                failure.getLastExecutionId(),
                failure.getLastResolvedExecutionId()
        );
    }
}
