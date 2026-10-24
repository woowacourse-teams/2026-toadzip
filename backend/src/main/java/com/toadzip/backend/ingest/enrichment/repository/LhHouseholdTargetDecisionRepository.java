package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdTargetDecision;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhHouseholdTargetDecisionRepository extends JpaRepository<LhHouseholdTargetDecision, Long> {
    Optional<LhHouseholdTargetDecision> findByHousingComplexId(long housingComplexId);
}
