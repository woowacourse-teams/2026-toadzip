package com.toadzip.backend.housing.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class MapClusteringTransitions {

    private final List<MapClusteringTransition> values;

    MapClusteringTransitions(List<MapClusteringTransition> transitions) {
        requireTransitions(transitions);
        values = transitions.stream()
                .sorted(Comparator.comparingInt(transition -> transition.fromStage().number()))
                .toList();
        new MapClusteringTransitionPolicyValidator(values).validate();
    }

    MapClusteringStage baseline(BigDecimal zoom) {
        return values.stream()
                .filter(transition -> zoom.compareTo(transition.boundaryZoom()) < 0)
                .findFirst()
                .map(MapClusteringTransition::fromStage)
                .orElse(MapClusteringStage.INDIVIDUAL);
    }

    MapClusteringTransition from(MapClusteringStage stage) {
        return values.stream()
                .filter(transition -> transition.fromStage() == stage)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing transition from " + stage));
    }

    MapClusteringTransition to(MapClusteringStage stage) {
        return values.stream()
                .filter(transition -> transition.toStage() == stage)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing transition to " + stage));
    }

    Optional<BigDecimal> expansionZoom(MapClusteringStage stage) {
        return values.stream()
                .filter(transition -> transition.fromStage() == stage)
                .map(MapClusteringTransition::expansionZoom)
                .findFirst();
    }

    private static void requireTransitions(List<MapClusteringTransition> transitions) {
        if (transitions != null) {
            return;
        }
        throw new IllegalArgumentException("Map clustering transitions are required");
    }
}
