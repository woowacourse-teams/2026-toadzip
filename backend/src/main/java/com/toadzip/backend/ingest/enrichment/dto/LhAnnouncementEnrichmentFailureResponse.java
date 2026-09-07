package com.toadzip.backend.ingest.enrichment.dto;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailureReason;
import java.time.Instant;

public record LhAnnouncementEnrichmentFailureResponse(
        String sourceKey,
        String sourceAnnouncementIdentifier,
        String panId,
        LhAnnouncementEnrichmentFailureReason reason,
        String detail,
        Instant occurredAt
) {

    public static LhAnnouncementEnrichmentFailureResponse from(LhAnnouncementEnrichmentFailure failure) {
        return new LhAnnouncementEnrichmentFailureResponse(
                failure.getSourceKey(), failure.getSourceAnnouncementIdentifier(), failure.getPanId(),
                failure.getReason(), failure.getDetail(), failure.getOccurredAt()
        );
    }
}
