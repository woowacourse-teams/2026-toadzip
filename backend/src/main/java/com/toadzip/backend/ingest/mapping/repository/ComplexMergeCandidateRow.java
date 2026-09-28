package com.toadzip.backend.ingest.mapping.repository;

import java.util.List;

public record ComplexMergeCandidateRow(
        long representativeId, String name, String roadAddress, String pnu, String provider, String supplyType,
        List<Source> sources
) {

    public record Source(long complexId, String sourceIdentifier, int householdCount) {
    }
}
