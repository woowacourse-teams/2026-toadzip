package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringRegionPoint;
import java.util.List;
import org.springframework.core.io.Resource;

final class MapClusteringRegionPointCsvRows {

    private static final String HEADER =
            "policyVersion,regionDatasetVersion,groupKey,latitude,longitude";

    private final List<MapClusteringRegionPointCsvRow> rows;
    private final Resource resource;

    private MapClusteringRegionPointCsvRows(
            List<MapClusteringRegionPointCsvRow> rows,
            Resource resource
    ) {
        requireRows(rows, resource);
        this.rows = rows;
        this.resource = resource;
        validateVersions();
    }

    static MapClusteringRegionPointCsvRows read(Resource resource) {
        MapClusteringCsvContent content = MapClusteringCsvContent.read(resource, HEADER);
        List<MapClusteringRegionPointCsvRow> rows = content.dataRows().stream()
                .map(line -> MapClusteringRegionPointCsvRow.parse(line, resource))
                .toList();
        return new MapClusteringRegionPointCsvRows(rows, resource);
    }

    MapClusteringPolicyVersion version() {
        return rows.getFirst().version();
    }

    List<MapClusteringRegionPoint> points() {
        return rows.stream().map(MapClusteringRegionPointCsvRow::point).toList();
    }

    Resource resource() {
        return resource;
    }

    private void validateVersions() {
        MapClusteringPolicyVersion expected = version();
        rows.forEach(row -> validateVersion(row, expected));
    }

    private void validateVersion(
            MapClusteringRegionPointCsvRow row,
            MapClusteringPolicyVersion expected
    ) {
        if (row.version().equals(expected)) {
            return;
        }
        throw MapClusteringPolicyCsvException.inconsistentVersion(resource, row.lineNumber());
    }

    private static void requireRows(List<MapClusteringRegionPointCsvRow> rows, Resource resource) {
        if (!rows.isEmpty()) {
            return;
        }
        throw MapClusteringPolicyCsvException.noData(resource);
    }
}
