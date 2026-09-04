package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringRegionMembership;
import java.util.List;
import org.springframework.core.io.Resource;

final class MapClusteringRegionMembershipCsvRows {

    private static final String HEADER = "policyVersion,regionDatasetVersion,canonicalRegionCode,"
            + "basicRegionGroupKey";

    private final List<MapClusteringRegionMembershipCsvRow> rows;
    private final Resource resource;

    private MapClusteringRegionMembershipCsvRows(
            List<MapClusteringRegionMembershipCsvRow> rows,
            Resource resource
    ) {
        requireRows(rows, resource);
        this.rows = rows;
        this.resource = resource;
        validateVersions();
    }

    static MapClusteringRegionMembershipCsvRows read(Resource resource) {
        MapClusteringCsvContent content = MapClusteringCsvContent.read(resource, HEADER);
        List<MapClusteringRegionMembershipCsvRow> rows = content.dataRows().stream()
                .map(line -> MapClusteringRegionMembershipCsvRow.parse(line, resource))
                .toList();
        return new MapClusteringRegionMembershipCsvRows(rows, resource);
    }

    MapClusteringPolicyVersion version() {
        return rows.getFirst().version();
    }

    List<MapClusteringRegionMembership> memberships() {
        return rows.stream()
                .map(MapClusteringRegionMembershipCsvRow::membership)
                .toList();
    }

    private void validateVersions() {
        MapClusteringPolicyVersion expected = version();
        rows.forEach(row -> validateVersion(row, expected));
    }

    private void validateVersion(
            MapClusteringRegionMembershipCsvRow row,
            MapClusteringPolicyVersion expected
    ) {
        if (row.version().equals(expected)) {
            return;
        }
        throw MapClusteringPolicyCsvException.inconsistentVersion(resource, row.lineNumber());
    }

    private static void requireRows(List<MapClusteringRegionMembershipCsvRow> rows, Resource resource) {
        if (!rows.isEmpty()) {
            return;
        }
        throw MapClusteringPolicyCsvException.noData(resource);
    }
}
