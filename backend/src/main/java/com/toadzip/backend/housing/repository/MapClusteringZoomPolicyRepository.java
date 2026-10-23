package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringZoomPolicy;
import com.toadzip.backend.housing.domain.MapClusteringProfile;

public interface MapClusteringZoomPolicyRepository {

    default MapClusteringZoomPolicy current() {
        return current(MapClusteringProfile.DEFAULT);
    }

    MapClusteringZoomPolicy current(MapClusteringProfile profile);
}
