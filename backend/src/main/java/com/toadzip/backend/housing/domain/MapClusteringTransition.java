package com.toadzip.backend.housing.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record MapClusteringTransition(
        MapClusteringStage fromStage,
        MapClusteringStage toStage,
        BigDecimal boundaryZoom,
        BigDecimal hysteresis,
        BigDecimal expansionZoom
) {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    public MapClusteringTransition {
        Objects.requireNonNull(fromStage, "fromStage");
        Objects.requireNonNull(toStage, "toStage");
        Objects.requireNonNull(boundaryZoom, "boundaryZoom");
        Objects.requireNonNull(hysteresis, "hysteresis");
        Objects.requireNonNull(expansionZoom, "expansionZoom");
        requireAdjacent(fromStage, toStage);
        requireNonNegative(boundaryZoom, "boundaryZoom");
        requireNonNegative(hysteresis, "hysteresis");
        requirePositiveExitZoom(boundaryZoom, hysteresis);
        requireExpansionZoom(boundaryZoom, hysteresis, expansionZoom);
    }

    BigDecimal enterZoom() {
        return boundaryZoom.add(hysteresis);
    }

    BigDecimal exitZoom() {
        return boundaryZoom.subtract(hysteresis);
    }

    private static void requireAdjacent(MapClusteringStage fromStage, MapClusteringStage toStage) {
        if (fromStage.next().filter(toStage::equals).isPresent()) {
            return;
        }
        throw new IllegalArgumentException("Map clustering transitions must connect adjacent stages");
    }

    private static void requireNonNegative(BigDecimal value, String fieldName) {
        if (value.compareTo(ZERO) >= 0) {
            return;
        }
        throw new IllegalArgumentException(fieldName + " must not be negative");
    }

    private static void requirePositiveExitZoom(BigDecimal boundaryZoom, BigDecimal hysteresis) {
        if (boundaryZoom.compareTo(hysteresis) > 0) {
            return;
        }
        throw new IllegalArgumentException("hysteresis must be less than boundaryZoom");
    }

    private static void requireExpansionZoom(
            BigDecimal boundaryZoom,
            BigDecimal hysteresis,
            BigDecimal expansionZoom
    ) {
        if (expansionZoom.compareTo(boundaryZoom.add(hysteresis)) >= 0) {
            return;
        }
        throw new IllegalArgumentException("expansionZoom must enter the next stage safe range");
    }
}
