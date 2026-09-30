package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LhHouseholdEnrichmentFailureRepository
        extends JpaRepository<LhHouseholdEnrichmentFailure, Long> {

    List<LhHouseholdEnrichmentFailure> findAllByStatusOrderBySourceKeyAscIdAsc(
            IngestFailureStatus status,
            Pageable pageable
    );

    List<LhHouseholdEnrichmentFailure> findAllByStatus(IngestFailureStatus status);

    List<LhHouseholdEnrichmentFailure> findAllBySourceKeyIn(Collection<String> sourceKeys);

    List<LhHouseholdEnrichmentFailure> findAllByOrderBySourceKeyAscIdAsc(Pageable pageable);
}
