package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.RoadAddressLocation;
import com.toadzip.backend.ingest.domain.RoadAddressLocationId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoadAddressLocationRepository
        extends JpaRepository<RoadAddressLocation, RoadAddressLocationId> {

    List<RoadAddressLocation> findAllByNormalizedRoadAddressOrderByIdEntranceSerialAsc(
            String normalizedRoadAddress
    );
}
