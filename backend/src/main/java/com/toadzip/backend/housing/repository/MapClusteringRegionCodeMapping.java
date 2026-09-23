package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import java.util.Objects;

record MapClusteringRegionCodeMapping(
        String storedRegionCode,
        MapClusteringGroupKey groupKey
) {

    MapClusteringRegionCodeMapping {
        if (storedRegionCode == null || !storedRegionCode.matches("\\d{5}")) {
            throw new IllegalArgumentException("Stored region code must be exactly five digits");
        }
        Objects.requireNonNull(groupKey, "groupKey");
    }
}
