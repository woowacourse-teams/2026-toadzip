package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringGroupKey;

public record MapClusteringRegionCountRow(
        MapClusteringGroupKey groupKey,
        long uniqueComplexCount
) {
}
