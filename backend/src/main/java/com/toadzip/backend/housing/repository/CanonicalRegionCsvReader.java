package com.toadzip.backend.housing.repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.Resource;

final class CanonicalRegionCsvReader {

    private static final String HEADER = "regionCode,sido,sigungu,name";
    private static final int COLUMN_COUNT = 4;

    CanonicalRegionCsvCatalog read(Resource resource) {
        MapClusteringCsvContent content = MapClusteringCsvContent.read(resource, HEADER);
        Set<String> regionCodes = new LinkedHashSet<>();
        content.dataRows().forEach(line -> addRegionCode(regionCodes, line, resource));
        return new CanonicalRegionCsvCatalog(
                content.requiredMetadata("effectiveDate"),
                Set.copyOf(regionCodes)
        );
    }

    private void addRegionCode(
            Set<String> regionCodes,
            MapClusteringCsvLine line,
            Resource resource
    ) {
        List<String> cells = List.of(line.value().split(",", -1));
        validateCells(cells, line.lineNumber(), resource);
        if (regionCodes.add(cells.getFirst())) {
            return;
        }
        throw invalidRegionRow(resource, line.lineNumber(), "duplicate regionCode '" + cells.getFirst() + "'");
    }

    private void validateCells(List<String> cells, int lineNumber, Resource resource) {
        if (cells.size() != COLUMN_COUNT) {
            throw MapClusteringPolicyCsvException.invalidColumnCount(resource, lineNumber, COLUMN_COUNT);
        }
        if (cells.getFirst().matches("\\d{5}")) {
            return;
        }
        throw invalidRegionRow(resource, lineNumber, "regionCode must be exactly five digits");
    }

    private IllegalStateException invalidRegionRow(Resource resource, int lineNumber, String reason) {
        return new IllegalStateException(
                "Invalid canonical region CSV " + resource.getDescription() + " at line " + lineNumber + ": " + reason
        );
    }
}
