package com.toadzip.backend.ingest.mapping.dto;

import java.time.Instant;
import java.util.List;

public record ComplexMergeCandidateResponse(
        long representativeId, String name, String roadAddress, String pnu, String provider, String supplyType,
        List<Source> sources, List<LhEvidence> lhEvidence
) {

    public record Source(long complexId, String sourceIdentifier, int householdCount) {
    }

    public record LhEvidence(long sourceId, String householdCount, Instant collectedAt) {
    }
}
