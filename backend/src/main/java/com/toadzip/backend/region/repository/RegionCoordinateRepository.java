package com.toadzip.backend.region.repository;

import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapCoordinate;
import com.toadzip.backend.housing.repository.MapClusteringRegionPointPolicyRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class RegionCoordinateRepository {

    private final MapClusteringRegionPointPolicyRepository pointPolicyRepository;

    public RegionCoordinateRepository(MapClusteringRegionPointPolicyRepository pointPolicyRepository) {
        this.pointPolicyRepository = pointPolicyRepository;
    }

    public Optional<MapCoordinate> findByRegionCode(String regionCode) {
        if (regionCode == null) {
            return Optional.empty();
        }
        if (regionCode.matches("\\d{2}")) {
            return pointPolicyRepository.current().coordinate(new MapClusteringGroupKey("METROPOLITAN:" + regionCode));
        }
        if (regionCode.matches("\\d{5}")) {
            return pointPolicyRepository.current().coordinate(new MapClusteringGroupKey("BASIC_REGION:" + regionCode));
        }
        return Optional.empty();
    }
}
