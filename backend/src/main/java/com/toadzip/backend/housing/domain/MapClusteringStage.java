package com.toadzip.backend.housing.domain;

import java.util.List;
import java.util.Optional;

public enum MapClusteringStage {

    SERVICE_ZONE(1),
    METROPOLITAN(2),
    BASIC_REGION(3),
    INDIVIDUAL(4);

    private static final List<MapClusteringStage> ORDERED_STAGES = List.of(values());

    private final int number;

    MapClusteringStage(int number) {
        this.number = number;
    }

    public static MapClusteringStage fromNumber(int number) {
        return ORDERED_STAGES.stream()
                .filter(stage -> stage.number == number)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown map clustering stage: " + number));
    }

    public int number() {
        return number;
    }

    public Optional<MapClusteringStage> next() {
        int nextIndex = ordinal() + 1;
        if (nextIndex >= ORDERED_STAGES.size()) {
            return Optional.empty();
        }
        return Optional.of(ORDERED_STAGES.get(nextIndex));
    }

    boolean isAdjacentTo(MapClusteringStage other) {
        return Math.abs(number - other.number) == 1;
    }

    boolean isAfter(MapClusteringStage other) {
        return number > other.number;
    }
}
