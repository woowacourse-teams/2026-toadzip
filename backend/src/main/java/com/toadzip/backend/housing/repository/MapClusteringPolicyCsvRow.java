package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringStage;
import com.toadzip.backend.housing.domain.MapClusteringTransition;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.core.io.Resource;

record MapClusteringPolicyCsvRow(
        MapClusteringPolicyVersion version,
        MapClusteringTransition transition,
        int lineNumber
) {

    private static final List<String> COLUMN_NAMES = List.of(
            "policyVersion",
            "regionDatasetVersion",
            "fromStage",
            "toStage",
            "boundaryZoom",
            "hysteresis",
            "expansionZoom"
    );

    static MapClusteringPolicyCsvRow parse(String line, int lineNumber, Resource resource) {
        List<String> cells = List.of(line.split(",", -1));
        validateCells(cells, lineNumber, resource);
        try {
            return create(cells, lineNumber);
        } catch (IllegalArgumentException exception) {
            throw MapClusteringPolicyCsvException.invalidRow(resource, lineNumber, exception);
        }
    }

    private static MapClusteringPolicyCsvRow create(List<String> cells, int lineNumber) {
        MapClusteringPolicyVersion version = new MapClusteringPolicyVersion(cells.get(0), cells.get(1));
        return new MapClusteringPolicyCsvRow(version, transition(cells), lineNumber);
    }

    private static MapClusteringTransition transition(List<String> cells) {
        return new MapClusteringTransition(
                stage(cells.get(2), "fromStage"),
                stage(cells.get(3), "toStage"),
                decimal(cells.get(4), "boundaryZoom"),
                decimal(cells.get(5), "hysteresis"),
                decimal(cells.get(6), "expansionZoom")
        );
    }

    private static MapClusteringStage stage(String value, String columnName) {
        return MapClusteringStage.fromNumber(integer(value, columnName));
    }

    private static int integer(String value, String columnName) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(columnName + " must be an integer", exception);
        }
    }

    private static BigDecimal decimal(String value, String columnName) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(columnName + " must be a decimal", exception);
        }
    }

    private static void validateCells(List<String> cells, int lineNumber, Resource resource) {
        if (cells.size() != COLUMN_NAMES.size()) {
            throw MapClusteringPolicyCsvException.invalidColumnCount(resource, lineNumber, COLUMN_NAMES.size());
        }
        IntStream.range(0, cells.size())
                .forEach(index -> validateCell(cells.get(index), COLUMN_NAMES.get(index), lineNumber, resource));
    }

    private static void validateCell(String value, String name, int lineNumber, Resource resource) {
        if (!value.isBlank()) {
            return;
        }
        throw MapClusteringPolicyCsvException.blankColumn(resource, lineNumber, name);
    }
}
