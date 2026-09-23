package com.toadzip.backend.housing.domain;

import java.util.Objects;

public record MapClusteringAggregateNode(
        MapClusteringRegionGroup group,
        MapCoordinate representativePoint,
        long uniqueComplexCount
) {

    public MapClusteringAggregateNode {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(representativePoint, "representativePoint");
        if (uniqueComplexCount < 0) {
            throw new IllegalArgumentException("uniqueComplexCount must not be negative");
        }
    }
}
