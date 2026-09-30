package com.toadzip.backend.ingest.enrichment.dto;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.time.Instant;
import java.util.UUID;

public record LhAnnouncementEnrichmentFailureResponse(
        String sourceKey,
        String sourceAnnouncementIdentifier,
        String panId,
        LhAnnouncementEnrichmentFailureReason reason,
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

    public LhAnnouncementEnrichmentFailureResponse(
            String sourceKey,
            String sourceAnnouncementIdentifier,
            String panId,
            LhAnnouncementEnrichmentFailureReason reason,
            String detail,
            Instant occurredAt
    ) {
        this(
                sourceKey, sourceAnnouncementIdentifier, panId, reason, detail,
                occurredAt, occurredAt, 1, 0,
                IngestFailureStatus.PENDING, null, null, null, null
        );
    }

    public static LhAnnouncementEnrichmentFailureResponse from(LhAnnouncementEnrichmentFailure failure) {
        return new LhAnnouncementEnrichmentFailureResponse(
                failure.getSourceKey(), failure.getSourceAnnouncementIdentifier(), failure.getPanId(),
                failure.getReason(), failure.getDetail(), failure.getOccurredAt(),
                failure.getLastOccurredAt(), failure.getOccurrenceCount(),
                failure.getRecurrenceCount(), failure.getStatus(), failure.getLastResolvedAt(),
                failure.getFirstExecutionId(), failure.getLastExecutionId(),
                failure.getLastResolvedExecutionId()
        );
    }
}
