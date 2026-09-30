package com.toadzip.backend.ingest.enrichment.dto;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailureReason;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.time.Instant;
import java.util.UUID;

public record LhHouseholdEnrichmentFailureResponse(
        String sourceKey,
        String areaName,
        String supplyTypeName,
        String complexName,
        LhHouseholdEnrichmentFailureReason reason,
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

    public static LhHouseholdEnrichmentFailureResponse from(
            LhHouseholdEnrichmentFailure failure
    ) {
        return new LhHouseholdEnrichmentFailureResponse(
                failure.getSourceKey(), failure.getAreaName(), failure.getSupplyTypeName(),
                failure.getComplexName(), failure.getReason(), failure.getDetail(),
                failure.getOccurredAt(), failure.getLastOccurredAt(),
                failure.getOccurrenceCount(), failure.getRecurrenceCount(), failure.getStatus(),
                failure.getLastResolvedAt(), failure.getFirstExecutionId(), failure.getLastExecutionId(),
                failure.getLastResolvedExecutionId()
        );
    }
}
