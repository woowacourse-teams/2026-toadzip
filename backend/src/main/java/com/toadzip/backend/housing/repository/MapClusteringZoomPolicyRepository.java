package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapClusteringZoomPolicy;

public interface MapClusteringZoomPolicyRepository {

    MapClusteringZoomPolicy current();
}
