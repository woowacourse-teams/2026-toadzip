package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringZoomPolicy;
import com.toadzip.backend.housing.domain.MapClusteringProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public final class CsvMapClusteringZoomPolicyRepository implements MapClusteringZoomPolicyRepository {

    private final MapClusteringZoomPolicy currentPolicy;
    private final MapClusteringZoomPolicy mobilePolicy;

    public CsvMapClusteringZoomPolicyRepository(
            @Value("classpath:map-clustering/stage-transitions.csv") Resource resource,
            @Value("classpath:map-clustering/stage-transitions-mobile.csv") Resource mobileResource
    ) {
        currentPolicy = new MapClusteringZoomPolicyCsvReader().read(resource);
        mobilePolicy = new MapClusteringZoomPolicyCsvReader().read(mobileResource);
    }

    @Override
    public MapClusteringZoomPolicy current(MapClusteringProfile profile) {
        if (profile == MapClusteringProfile.MOBILE) {
            return mobilePolicy;
        }
        return currentPolicy;
    }
}
