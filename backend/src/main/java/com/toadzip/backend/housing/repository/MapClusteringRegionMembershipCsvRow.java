package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringRegionMembership;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.core.io.Resource;

record MapClusteringRegionMembershipCsvRow(
        MapClusteringPolicyVersion version,
        MapClusteringRegionMembership membership,
        int lineNumber
) {

    private static final List<String> COLUMN_NAMES = List.of(
            "policyVersion",
            "regionDatasetVersion",
            "canonicalRegionCode",
            "basicRegionGroupKey"
    );

    static MapClusteringRegionMembershipCsvRow parse(MapClusteringCsvLine line, Resource resource) {
        List<String> cells = List.of(line.value().split(",", -1));
        validateCells(cells, line.lineNumber(), resource);
        try {
            return create(cells, line.lineNumber());
        } catch (IllegalArgumentException exception) {
            throw MapClusteringPolicyCsvException.invalidRow(resource, line.lineNumber(), exception);
        }
    }

    private static MapClusteringRegionMembershipCsvRow create(List<String> cells, int lineNumber) {
        MapClusteringPolicyVersion version = new MapClusteringPolicyVersion(cells.get(0), cells.get(1));
        MapClusteringRegionMembership membership = new MapClusteringRegionMembership(
                cells.get(2),
                new MapClusteringGroupKey(cells.get(3))
        );
        return new MapClusteringRegionMembershipCsvRow(version, membership, lineNumber);
    }

    private static void validateCells(List<String> cells, int lineNumber, Resource resource) {
        if (cells.size() != COLUMN_NAMES.size()) {
            throw MapClusteringPolicyCsvException.invalidColumnCount(resource, lineNumber, COLUMN_NAMES.size());
        }
        IntStream.range(0, COLUMN_NAMES.size())
                .forEach(index -> validateCell(cells.get(index), COLUMN_NAMES.get(index), lineNumber, resource));
    }

    private static void validateCell(String value, String name, int lineNumber, Resource resource) {
        if (!value.isBlank()) {
            return;
        }
        throw MapClusteringPolicyCsvException.blankColumn(resource, lineNumber, name);
    }
}
