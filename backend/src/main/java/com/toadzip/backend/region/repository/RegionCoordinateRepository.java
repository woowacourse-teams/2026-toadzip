package com.toadzip.backend.region.repository;

import com.toadzip.backend.housing.domain.MapClusteringGroupKey;
import com.toadzip.backend.housing.domain.MapCoordinate;
import com.toadzip.backend.housing.repository.MapClusteringRegionPointPolicyRepository;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class RegionCoordinateRepository {

    private final MapClusteringRegionPointPolicyRepository pointPolicyRepository;
    private final NeighborhoodCoordinateRepository neighborhoodCoordinateRepository;

    public RegionCoordinateRepository(MapClusteringRegionPointPolicyRepository pointPolicyRepository,
            NeighborhoodCoordinateRepository neighborhoodCoordinateRepository) {
        this.pointPolicyRepository = pointPolicyRepository;
        this.neighborhoodCoordinateRepository = neighborhoodCoordinateRepository;
    }

    public Optional<MapCoordinate> findByRegionCode(String regionCode) {
        if (regionCode == null) {
            return Optional.empty();
        }
        if (regionCode.matches("[0-9]{8}00") && !regionCode.endsWith("00000")) {
            return neighborhoodCoordinateRepository.findByCode(regionCode);
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
