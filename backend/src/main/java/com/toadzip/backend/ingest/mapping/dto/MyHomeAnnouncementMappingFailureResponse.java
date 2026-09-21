package com.toadzip.backend.ingest.mapping.dto;

import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailureReason;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.time.Instant;
import java.util.UUID;

public record MyHomeAnnouncementMappingFailureResponse(
        String sourceKey,
        String sourceAnnouncementIdentifier,
        Integer sourceHouseSerialNumber,
        MyHomeAnnouncementMappingFailureReason reason,
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

    public MyHomeAnnouncementMappingFailureResponse(
            String sourceKey,
            String sourceAnnouncementIdentifier,
            Integer sourceHouseSerialNumber,
            MyHomeAnnouncementMappingFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        this(
                sourceKey, sourceAnnouncementIdentifier, sourceHouseSerialNumber,
                reason, detail, occurredAt, occurredAt, 1, 0,
                IngestFailureStatus.PENDING, null, null, null, null
        );
    }

    public static MyHomeAnnouncementMappingFailureResponse from(MyHomeAnnouncementMappingFailure failure) {
        return new MyHomeAnnouncementMappingFailureResponse(
                failure.getSourceKey(),
                failure.getSourceAnnouncementIdentifier(),
                failure.getSourceHouseSerialNumber(),
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
