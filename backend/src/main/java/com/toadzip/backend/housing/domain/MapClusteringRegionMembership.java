package com.toadzip.backend.housing.domain;

import java.util.Objects;

public record MapClusteringRegionMembership(
        String canonicalRegionCode,
        MapClusteringGroupKey basicRegionGroupKey
) {

    public MapClusteringRegionMembership {
        if (canonicalRegionCode == null || !canonicalRegionCode.matches("\\d{5}")) {
            throw new IllegalArgumentException("Canonical region code must be exactly five digits");
        }
        Objects.requireNonNull(basicRegionGroupKey, "basicRegionGroupKey");
    }
}
