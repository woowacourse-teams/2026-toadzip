package com.toadzip.backend.housing.domain;

import java.util.Objects;
import java.util.Optional;

public record MapClusteringRegionGroup(
        MapClusteringGroupKey key,
        String label,
        MapClusteringStage stage,
        Optional<MapClusteringGroupKey> parentKey
) {

    public MapClusteringRegionGroup {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(parentKey, "parentKey");
        requireLabel(label);
        key.requireStage(stage);
    }

    private static void requireLabel(String label) {
        if (label != null && !label.isBlank()) {
            return;
        }
        throw new IllegalArgumentException("Map clustering region group label is required");
    }
}
