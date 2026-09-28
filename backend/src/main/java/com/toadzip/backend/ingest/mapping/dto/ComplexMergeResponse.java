package com.toadzip.backend.ingest.mapping.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ComplexMergeResponse(
        UUID operationId, long representativeId, List<Long> complexIds, int adoptedHouseholdCount,
        String reason, String verifiedBy, Instant mergedAt, String revertedBy, Instant revertedAt,
        String evidence
) {
}
