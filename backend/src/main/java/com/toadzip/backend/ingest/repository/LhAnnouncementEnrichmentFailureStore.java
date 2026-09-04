package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.LhAnnouncementEnrichmentFailure;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LhAnnouncementEnrichmentFailureStore {

    private final LhAnnouncementEnrichmentFailureRepository repository;

    public LhAnnouncementEnrichmentFailureStore(LhAnnouncementEnrichmentFailureRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void replaceAll(List<LhAnnouncementEnrichmentFailure> failures) {
        repository.deleteAllInBatch();
        repository.saveAll(failures);
    }
}
