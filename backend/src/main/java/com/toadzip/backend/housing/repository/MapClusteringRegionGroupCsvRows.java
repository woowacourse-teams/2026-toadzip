package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringRegionGroup;
import java.util.List;
import org.springframework.core.io.Resource;

final class MapClusteringRegionGroupCsvRows {

    private static final String HEADER = "policyVersion,regionDatasetVersion,stage,groupKey,groupLabel,"
            + "parentGroupKey";

    private final List<MapClusteringRegionGroupCsvRow> rows;
    private final Resource resource;

    private MapClusteringRegionGroupCsvRows(
            List<MapClusteringRegionGroupCsvRow> rows,
            Resource resource
    ) {
        requireRows(rows, resource);
        this.rows = rows;
        this.resource = resource;
        validateVersions();
    }

    static MapClusteringRegionGroupCsvRows read(Resource resource) {
        MapClusteringCsvContent content = MapClusteringCsvContent.read(resource, HEADER);
        List<MapClusteringRegionGroupCsvRow> rows = content.dataRows().stream()
                .map(line -> MapClusteringRegionGroupCsvRow.parse(line, resource))
                .toList();
        return new MapClusteringRegionGroupCsvRows(rows, resource);
    }

    MapClusteringPolicyVersion version() {
        return rows.getFirst().version();
    }

    List<MapClusteringRegionGroup> groups() {
        return rows.stream().map(MapClusteringRegionGroupCsvRow::group).toList();
    }

    Resource resource() {
        return resource;
    }

    private void validateVersions() {
        MapClusteringPolicyVersion expected = version();
        rows.forEach(row -> validateVersion(row, expected));
    }

    private void validateVersion(
            MapClusteringRegionGroupCsvRow row,
            MapClusteringPolicyVersion expected
    ) {
        if (row.version().equals(expected)) {
            return;
        }
        throw MapClusteringPolicyCsvException.inconsistentVersion(resource, row.lineNumber());
    }

    private static void requireRows(List<MapClusteringRegionGroupCsvRow> rows, Resource resource) {
        if (!rows.isEmpty()) {
            return;
        }
        throw MapClusteringPolicyCsvException.noData(resource);
    }
}
