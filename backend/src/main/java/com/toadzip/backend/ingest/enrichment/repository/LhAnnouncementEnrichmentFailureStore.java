package com.toadzip.backend.ingest.enrichment.repository;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.failure.repository.IngestFailureStore;
import java.time.Clock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LhAnnouncementEnrichmentFailureStore extends IngestFailureStore<LhAnnouncementEnrichmentFailure> {

    private final LhAnnouncementEnrichmentFailureRepository repository;

    public LhAnnouncementEnrichmentFailureStore(LhAnnouncementEnrichmentFailureRepository repository, Clock clock) {
        super(repository, clock);
        this.repository = repository;
    }

    @Transactional
    public void resolveByAdmin(String identifier, String actor) {
        resolvePendingByAdmin(repository.findAllBySourceAnnouncementIdentifier(identifier), actor);
    }
}
