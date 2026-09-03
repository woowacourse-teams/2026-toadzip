package com.toadzip.backend.housing.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MapClusteringZoomPolicy {

    private final MapClusteringPolicyVersion version;
    private final MapClusteringTransitions transitions;
    private final MapClusteringStageResolver stageResolver;

    private MapClusteringZoomPolicy(
            MapClusteringPolicyVersion version,
            MapClusteringTransitions transitions
    ) {
        this.version = version;
        this.transitions = transitions;
        stageResolver = new MapClusteringStageResolver(transitions);
    }

    public static MapClusteringZoomPolicy of(
            MapClusteringPolicyVersion version,
            List<MapClusteringTransition> transitions
    ) {
        Objects.requireNonNull(version, "version");
        return new MapClusteringZoomPolicy(version, new MapClusteringTransitions(transitions));
    }

    public MapClusteringStage resolveStage(BigDecimal zoom, MapClusteringStage previousStage) {
        return stageResolver.resolve(zoom, previousStage);
    }

    public Optional<BigDecimal> expansionZoom(MapClusteringStage stage) {
        Objects.requireNonNull(stage, "stage");
        return transitions.expansionZoom(stage);
    }

    public String policyVersion() {
        return version.policyVersion();
    }

    public String regionDatasetVersion() {
        return version.regionDatasetVersion();
    }
}
