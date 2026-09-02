package com.toadzip.backend.housing.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

final class MapClusteringTransitionPolicyValidator {

    private static final int EXPECTED_TRANSITION_COUNT = 3;

    private final List<MapClusteringTransition> transitions;

    MapClusteringTransitionPolicyValidator(List<MapClusteringTransition> transitions) {
        this.transitions = transitions;
    }

    void validate() {
        requireCount();
        requireComplete();
        requireSeparatedHysteresisRanges();
        requireExpansionTargets();
    }

    private void requireCount() {
        if (transitions.size() == EXPECTED_TRANSITION_COUNT) {
            return;
        }
        throw new IllegalArgumentException("Exactly three map clustering transitions are required");
    }

    private void requireComplete() {
        aggregateStages().forEach(this::transitionFrom);
    }

    private List<MapClusteringStage> aggregateStages() {
        return List.of(
                MapClusteringStage.SERVICE_ZONE,
                MapClusteringStage.METROPOLITAN,
                MapClusteringStage.BASIC_REGION
        );
    }

    private void requireSeparatedHysteresisRanges() {
        IntStream.range(0, transitions.size() - 1)
                .forEach(this::requireSeparatedHysteresisRange);
    }

    private void requireSeparatedHysteresisRange(int index) {
        BigDecimal currentEnterZoom = transitions.get(index).enterZoom();
        BigDecimal nextExitZoom = transitions.get(index + 1).exitZoom();
        if (currentEnterZoom.compareTo(nextExitZoom) < 0) {
            return;
        }
        throw new IllegalArgumentException("Map clustering hysteresis ranges must not overlap");
    }

    private void requireExpansionTargets() {
        transitions.forEach(this::requireExpansionTarget);
    }

    private void requireExpansionTarget(MapClusteringTransition transition) {
        if (baseline(transition.expansionZoom()) == transition.toStage()) {
            return;
        }
        throw new IllegalArgumentException("expansionZoom must target exactly the next stage");
    }

    private MapClusteringStage baseline(BigDecimal zoom) {
        return transitions.stream()
                .filter(transition -> zoom.compareTo(transition.boundaryZoom()) < 0)
                .findFirst()
                .map(MapClusteringTransition::fromStage)
                .orElse(MapClusteringStage.INDIVIDUAL);
    }

    private MapClusteringTransition transitionFrom(MapClusteringStage stage) {
        return transitions.stream()
                .filter(transition -> transition.fromStage() == stage)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing transition from " + stage));
    }
}
