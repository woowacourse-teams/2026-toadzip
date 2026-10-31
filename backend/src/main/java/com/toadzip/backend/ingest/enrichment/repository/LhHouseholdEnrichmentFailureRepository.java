package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import com.toadzip.backend.ingest.failure.repository.IngestFailureRepository;
import java.util.List;
import org.springframework.data.domain.Pageable;

public interface LhHouseholdEnrichmentFailureRepository
        extends IngestFailureRepository<LhHouseholdEnrichmentFailure> {

    List<LhHouseholdEnrichmentFailure> findAllByStatusOrderBySourceKeyAscIdAsc(
            IngestFailureStatus status,
            Pageable pageable
    );

    List<LhHouseholdEnrichmentFailure> findAllByStatus(IngestFailureStatus status);

    List<LhHouseholdEnrichmentFailure> findAllByOrderBySourceKeyAscIdAsc(Pageable pageable);
}
