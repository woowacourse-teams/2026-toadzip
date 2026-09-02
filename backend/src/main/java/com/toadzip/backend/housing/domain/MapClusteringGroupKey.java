package com.toadzip.backend.housing.domain;

import java.util.Map;
import java.util.regex.Pattern;

public record MapClusteringGroupKey(String value) {

    private static final Map<MapClusteringStage, Pattern> KEY_PATTERNS = Map.of(
            MapClusteringStage.SERVICE_ZONE, Pattern.compile("SERVICE_ZONE:[A-Z0-9_]+"),
            MapClusteringStage.METROPOLITAN, Pattern.compile("METROPOLITAN:\\d{2}"),
            MapClusteringStage.BASIC_REGION, Pattern.compile("BASIC_REGION:\\d{5}")
    );

    public MapClusteringGroupKey {
        requireValue(value);
    }

    private static void requireValue(String value) {
        if (value != null && !value.isBlank()) {
            return;
        }
        throw new IllegalArgumentException("Map clustering group key is required");
    }

    void requireStage(MapClusteringStage stage) {
        Pattern pattern = KEY_PATTERNS.get(stage);
        if (pattern != null && pattern.matcher(value).matches()) {
            return;
        }
        throw new IllegalArgumentException("Map clustering group key does not match stage " + stage);
    }
}
