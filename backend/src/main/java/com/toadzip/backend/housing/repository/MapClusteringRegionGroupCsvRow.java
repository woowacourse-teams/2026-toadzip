package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringRegionGroup;
import com.toadzip.backend.housing.domain.MapClusteringStage;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.springframework.core.io.Resource;

record MapClusteringRegionGroupCsvRow(
        MapClusteringPolicyVersion version,
        MapClusteringRegionGroup group,
        int lineNumber
) {

    private static final List<String> COLUMN_NAMES = List.of(
            "policyVersion",
            "regionDatasetVersion",
            "stage",
            "groupKey",
            "groupLabel",
            "parentGroupKey"
    );

    static MapClusteringRegionGroupCsvRow parse(MapClusteringCsvLine line, Resource resource) {
        List<String> cells = List.of(line.value().split(",", -1));
        validateCells(cells, line.lineNumber(), resource);
        try {
            return create(cells, line.lineNumber());
        } catch (IllegalArgumentException exception) {
            throw MapClusteringPolicyCsvException.invalidRow(resource, line.lineNumber(), exception);
        }
    }

    private static MapClusteringRegionGroupCsvRow create(List<String> cells, int lineNumber) {
        MapClusteringPolicyVersion version = new MapClusteringPolicyVersion(cells.get(0), cells.get(1));
        MapClusteringRegionGroup group = new MapClusteringRegionGroup(
                new MapClusteringGroupKey(cells.get(3)),
                cells.get(4),
                stage(cells.get(2)),
                parentKey(cells.get(5))
        );
        return new MapClusteringRegionGroupCsvRow(version, group, lineNumber);
    }

    private static MapClusteringStage stage(String value) {
        try {
            return MapClusteringStage.fromNumber(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("stage must be an integer", exception);
        }
    }

    private static Optional<MapClusteringGroupKey> parentKey(String value) {
        if (value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new MapClusteringGroupKey(value));
    }

    private static void validateCells(List<String> cells, int lineNumber, Resource resource) {
        if (cells.size() != COLUMN_NAMES.size()) {
            throw MapClusteringPolicyCsvException.invalidColumnCount(resource, lineNumber, COLUMN_NAMES.size());
        }
        IntStream.range(0, COLUMN_NAMES.size() - 1)
                .forEach(index -> validateCell(cells.get(index), COLUMN_NAMES.get(index), lineNumber, resource));
    }

    private static void validateCell(String value, String name, int lineNumber, Resource resource) {
        if (!value.isBlank()) {
            return;
        }
        throw MapClusteringPolicyCsvException.blankColumn(resource, lineNumber, name);
    }
}
