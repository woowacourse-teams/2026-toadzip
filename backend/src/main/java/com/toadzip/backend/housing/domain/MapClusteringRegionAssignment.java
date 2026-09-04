package com.toadzip.backend.housing.domain;

import java.util.Objects;

public record MapClusteringRegionAssignment(
        String canonicalRegionCode,
        MapClusteringGroupKey groupKey
) {

    public MapClusteringRegionAssignment {
        if (canonicalRegionCode == null || !canonicalRegionCode.matches("\\d{5}")) {
            throw new IllegalArgumentException("Canonical region code must be exactly five digits");
        }
        Objects.requireNonNull(groupKey, "groupKey");
    }
}
