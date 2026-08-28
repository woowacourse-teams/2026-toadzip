package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.HousingComplex;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HousingComplexRepository extends JpaRepository<HousingComplex, Long> {

    Optional<HousingComplex> findBySourceComplexIdentifier(String sourceComplexIdentifier);
}
