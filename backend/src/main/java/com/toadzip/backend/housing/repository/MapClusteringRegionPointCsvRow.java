package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringRegionPoint;
import com.toadzip.backend.housing.domain.MapCoordinate;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.core.io.Resource;

record MapClusteringRegionPointCsvRow(
        MapClusteringPolicyVersion version,
        MapClusteringRegionPoint point,
        int lineNumber
) {

    private static final List<String> COLUMN_NAMES = List.of(
            "policyVersion", "regionDatasetVersion", "groupKey", "latitude", "longitude"
    );

    static MapClusteringRegionPointCsvRow parse(MapClusteringCsvLine line, Resource resource) {
        List<String> cells = List.of(line.value().split(",", -1));
        validateCells(cells, line.lineNumber(), resource);
        try {
            return create(cells, line.lineNumber());
        } catch (IllegalArgumentException exception) {
            throw MapClusteringPolicyCsvException.invalidRow(resource, line.lineNumber(), exception);
        }
    }

    private static MapClusteringRegionPointCsvRow create(List<String> cells, int lineNumber) {
        MapClusteringPolicyVersion version = new MapClusteringPolicyVersion(cells.get(0), cells.get(1));
        MapCoordinate coordinate = new MapCoordinate(
                decimal(cells.get(3), "latitude"),
                decimal(cells.get(4), "longitude")
        );
        MapClusteringRegionPoint point = new MapClusteringRegionPoint(
                new MapClusteringGroupKey(cells.get(2)), coordinate
        );
        return new MapClusteringRegionPointCsvRow(version, point, lineNumber);
    }

    private static BigDecimal decimal(String value, String name) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a decimal", exception);
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
