package com.toadzip.backend.housing.domain;

import java.util.Objects;

public record MapClusteringRegionPoint(
        MapClusteringGroupKey groupKey,
        MapCoordinate coordinate
) {

    public MapClusteringRegionPoint {
        Objects.requireNonNull(groupKey, "groupKey");
        Objects.requireNonNull(coordinate, "coordinate");
    }
}
