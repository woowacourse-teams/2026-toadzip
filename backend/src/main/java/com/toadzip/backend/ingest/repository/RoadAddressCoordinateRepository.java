package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.JusoAddressCode;
import com.toadzip.backend.ingest.domain.RoadAddressCandidate;
import com.toadzip.backend.ingest.domain.UtmKCoordinate;
import java.util.List;
import java.util.Optional;

public interface RoadAddressCoordinateRepository {

    List<RoadAddressCandidate> search(String roadAddress);

    Optional<UtmKCoordinate> findCoordinate(JusoAddressCode addressCode);
}
