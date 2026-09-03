package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringRegionPointPolicy;
import com.toadzip.backend.housing.domain.MapClusteringRegionPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public final class CsvMapClusteringRegionPointPolicyRepository
        implements MapClusteringRegionPointPolicyRepository {

    private final MapClusteringRegionPointPolicy currentPolicy;

    public CsvMapClusteringRegionPointPolicyRepository(
            @Value("classpath:map-clustering/representative-points.csv") Resource resource,
            MapClusteringRegionPolicyRepository regionPolicyRepository
    ) {
        currentPolicy = new MapClusteringRegionPointPolicyCsvReader().read(resource);
        validateRegionPolicy(regionPolicyRepository.current());
    }

    @Override
    public MapClusteringRegionPointPolicy current() {
        return currentPolicy;
    }

    private void validateRegionPolicy(MapClusteringRegionPolicy regionPolicy) {
        if (sameVersion(regionPolicy)) {
            validateGroups(regionPolicy);
            return;
        }
        throw new IllegalStateException("Map clustering representative point policy differs from region policy: "
                + "policyVersion or regionDatasetVersion");
    }

    private boolean sameVersion(MapClusteringRegionPolicy regionPolicy) {
        return currentPolicy.policyVersion().equals(regionPolicy.policyVersion())
                && currentPolicy.regionDatasetVersion().equals(regionPolicy.regionDatasetVersion());
    }

    private void validateGroups(MapClusteringRegionPolicy regionPolicy) {
        try {
            currentPolicy.validateGroups(regionPolicy.groups());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }
}
