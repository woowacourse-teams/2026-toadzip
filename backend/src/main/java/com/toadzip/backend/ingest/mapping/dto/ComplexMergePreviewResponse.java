package com.toadzip.backend.ingest.mapping.dto;

import java.time.Instant;
import java.util.List;

public record ComplexMergePreviewResponse(
        long representativeId, List<Long> complexIds, int adoptedHouseholdCount, String previewHash,
        List<Source> sources, long lhSourceId, Instant lhCollectedAt, String lhRegion, String lhName
) {

    public record Source(String sourceComplexIdentifier, int householdCount, Instant collectedAt) {
    }
}
