package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.failure.repository.IngestFailureStore;
import java.time.Clock;
import org.springframework.stereotype.Repository;

@Repository
public class LhHouseholdEnrichmentFailureStore extends IngestFailureStore<LhHouseholdEnrichmentFailure> {

    public LhHouseholdEnrichmentFailureStore(LhHouseholdEnrichmentFailureRepository repository, Clock clock) {
        super(repository, clock);
    }
}
