package com.toadzip.backend.geocoding.repository;

import com.toadzip.backend.geocoding.domain.JusoAddressCode;
import com.toadzip.backend.geocoding.domain.RoadAddressCandidate;
import com.toadzip.backend.geocoding.domain.UtmKCoordinate;
import java.util.List;
import java.util.Optional;

public interface RoadAddressCoordinateRepository {

    List<RoadAddressCandidate> search(String roadAddress);

    Optional<UtmKCoordinate> findCoordinate(JusoAddressCode addressCode);
}
