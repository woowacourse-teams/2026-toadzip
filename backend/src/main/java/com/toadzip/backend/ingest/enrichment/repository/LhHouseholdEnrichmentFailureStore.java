package com.toadzip.backend.ingest.enrichment.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.failure.repository.IngestFailureStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LhHouseholdEnrichmentFailureStore extends IngestFailureStore<LhHouseholdEnrichmentFailure> {

    private final LhHouseholdEnrichmentFailureRepository repository;

    public LhHouseholdEnrichmentFailureStore(LhHouseholdEnrichmentFailureRepository repository, Clock clock) {
        super(repository, clock);
        this.repository = repository;
    }

    @Transactional
    public void reconcileAfterRun(List<LhHouseholdEnrichmentFailure> failures, UUID executionId) {
        reconcileWithHistory(repository.findAllByStatus(PENDING), failures, executionId);
    }
}
