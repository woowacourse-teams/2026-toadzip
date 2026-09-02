package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringZoomPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public final class CsvMapClusteringZoomPolicyRepository implements MapClusteringZoomPolicyRepository {

    private final MapClusteringZoomPolicy currentPolicy;

    public CsvMapClusteringZoomPolicyRepository(
            @Value("classpath:map-clustering/stage-transitions.csv") Resource resource
    ) {
        currentPolicy = new MapClusteringZoomPolicyCsvReader().read(resource);
    }

    @Override
    public MapClusteringZoomPolicy current() {
        return currentPolicy;
    }
}
