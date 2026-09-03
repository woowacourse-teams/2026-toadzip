package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringRegionPointPolicy;
import org.springframework.core.io.Resource;

final class MapClusteringRegionPointPolicyCsvReader {

    MapClusteringRegionPointPolicy read(Resource resource) {
        MapClusteringRegionPointCsvRows rows = MapClusteringRegionPointCsvRows.read(resource);
        try {
            return MapClusteringRegionPointPolicy.of(rows.version(), rows.points());
        } catch (IllegalArgumentException exception) {
            throw MapClusteringPolicyCsvException.invalidPolicy(rows.resource(), exception);
        }
    }
}
