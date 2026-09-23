package com.toadzip.backend.housing.domain;

import java.util.Objects;

public record MapClusteringRegionCandidate(
        MapClusteringRegionGroup group,
        MapCoordinate representativePoint
) {

    public MapClusteringRegionCandidate {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(representativePoint, "representativePoint");
    }
}
