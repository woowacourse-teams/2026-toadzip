package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhHouseholdEnrichmentFailureRepository
        extends JpaRepository<LhHouseholdEnrichmentFailure, Long> {

    List<LhHouseholdEnrichmentFailure> findAllByStatusOrderBySourceKeyAsc(
            IngestFailureStatus status
    );

    List<LhHouseholdEnrichmentFailure> findAllByOrderBySourceKeyAsc();
}
