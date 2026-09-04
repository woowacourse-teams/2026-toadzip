package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringRegionPolicy;
import com.toadzip.backend.housing.domain.MapClusteringZoomPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public final class CsvMapClusteringRegionPolicyRepository implements MapClusteringRegionPolicyRepository {

    private final MapClusteringRegionPolicy currentPolicy;

    public CsvMapClusteringRegionPolicyRepository(
            @Value("classpath:map-clustering/groups.csv") Resource groupResource,
            @Value("classpath:map-clustering/memberships.csv") Resource membershipResource,
            @Value("classpath:region/regions.csv") Resource canonicalRegionResource,
            MapClusteringZoomPolicyRepository zoomPolicyRepository
    ) {
        currentPolicy = new MapClusteringRegionPolicyCsvReader().read(
                groupResource,
                membershipResource,
                canonicalRegionResource
        );
        validateZoomPolicyVersion(zoomPolicyRepository.current());
    }

    @Override
    public MapClusteringRegionPolicy current() {
        return currentPolicy;
    }

    private void validateZoomPolicyVersion(MapClusteringZoomPolicy zoomPolicy) {
        if (sameVersion(zoomPolicy)) {
            return;
        }
        throw new IllegalStateException("Map clustering region policy differs from zoom policy: "
                + "policyVersion or regionDatasetVersion");
    }

    private boolean sameVersion(MapClusteringZoomPolicy zoomPolicy) {
        return currentPolicy.policyVersion().equals(zoomPolicy.policyVersion())
                && currentPolicy.regionDatasetVersion().equals(zoomPolicy.regionDatasetVersion());
    }
}
