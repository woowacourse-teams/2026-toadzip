package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringPolicyVersion;
import com.toadzip.backend.housing.domain.MapClusteringZoomPolicy;
import java.util.List;
import org.springframework.core.io.Resource;

final class MapClusteringPolicyCsvRows {

    private final List<MapClusteringPolicyCsvRow> rows;
    private final Resource resource;

    MapClusteringPolicyCsvRows(List<MapClusteringPolicyCsvRow> rows, Resource resource) {
        requireRows(rows, resource);
        this.rows = rows;
        this.resource = resource;
        validateVersions();
    }

    MapClusteringZoomPolicy toPolicy() {
        try {
            return MapClusteringZoomPolicy.of(
                    rows.getFirst().version(),
                    rows.stream().map(MapClusteringPolicyCsvRow::transition).toList()
            );
        } catch (IllegalArgumentException exception) {
            throw MapClusteringPolicyCsvException.invalidPolicy(resource, exception);
        }
    }

    private void validateVersions() {
        MapClusteringPolicyVersion expected = rows.getFirst().version();
        rows.forEach(row -> validateVersion(row, expected));
    }

    private void validateVersion(MapClusteringPolicyCsvRow row, MapClusteringPolicyVersion expected) {
        if (row.version().equals(expected)) {
            return;
        }
        throw MapClusteringPolicyCsvException.inconsistentVersion(resource, row.lineNumber());
    }

    private static void requireRows(List<MapClusteringPolicyCsvRow> rows, Resource resource) {
        if (!rows.isEmpty()) {
            return;
        }
        throw MapClusteringPolicyCsvException.noData(resource);
    }
}
