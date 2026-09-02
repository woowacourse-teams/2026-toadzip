package com.toadzip.backend.housing.domain;

import java.math.BigDecimal;

final class MapClusteringStageResolver {

    private final MapClusteringTransitions transitions;

    MapClusteringStageResolver(MapClusteringTransitions transitions) {
        this.transitions = transitions;
    }

    MapClusteringStage resolve(BigDecimal zoom, MapClusteringStage previousStage) {
        requireZoom(zoom);
        MapClusteringStage baseline = transitions.baseline(zoom);
        if (previousStage == null || previousStage == baseline) {
            return baseline;
        }
        if (!previousStage.isAdjacentTo(baseline)) {
            return baseline;
        }
        return resolveAdjacent(zoom, previousStage, baseline);
    }

    private MapClusteringStage resolveAdjacent(
            BigDecimal zoom,
            MapClusteringStage previousStage,
            MapClusteringStage baseline
    ) {
        if (baseline.isAfter(previousStage)) {
            return resolveZoomIn(zoom, previousStage, baseline);
        }
        return resolveZoomOut(zoom, previousStage, baseline);
    }

    private MapClusteringStage resolveZoomIn(
            BigDecimal zoom,
            MapClusteringStage previousStage,
            MapClusteringStage baseline
    ) {
        BigDecimal enterZoom = transitions.from(previousStage).enterZoom();
        if (zoom.compareTo(enterZoom) < 0) {
            return previousStage;
        }
        return baseline;
    }

    private MapClusteringStage resolveZoomOut(
            BigDecimal zoom,
            MapClusteringStage previousStage,
            MapClusteringStage baseline
    ) {
        BigDecimal exitZoom = transitions.to(previousStage).exitZoom();
        if (zoom.compareTo(exitZoom) >= 0) {
            return previousStage;
        }
        return baseline;
    }

    private static void requireZoom(BigDecimal zoom) {
        if (zoom != null && zoom.signum() >= 0) {
            return;
        }
        throw new IllegalArgumentException("zoom must not be null or negative");
    }
}
