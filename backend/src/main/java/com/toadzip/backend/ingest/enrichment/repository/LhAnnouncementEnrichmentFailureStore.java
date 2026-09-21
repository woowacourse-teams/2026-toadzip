package com.toadzip.backend.ingest.enrichment.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LhAnnouncementEnrichmentFailureStore {

    private final LhAnnouncementEnrichmentFailureRepository repository;
    private final Clock clock;

    public LhAnnouncementEnrichmentFailureStore(
            LhAnnouncementEnrichmentFailureRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void replaceAll(List<LhAnnouncementEnrichmentFailure> failures, UUID executionId) {
        Instant resolvedAt = clock.instant();
        Map<FailureKey, LhAnnouncementEnrichmentFailure> stored = indexed(repository.findAll());
        Map<FailureKey, LhAnnouncementEnrichmentFailure> observed = indexed(failures);
        stored.forEach((key, failure) -> {
            if (failure.getStatus() == PENDING && !observed.containsKey(key)) {
                failure.resolve(resolvedAt, executionId);
            }
        });
        observed.forEach((key, failure) -> {
            LhAnnouncementEnrichmentFailure existing = stored.get(key);
            if (existing == null) {
                failure.attachFirstExecution(executionId);
                repository.save(failure);
                return;
            }
            existing.observe(failure, executionId);
        });
    }

    private Map<FailureKey, LhAnnouncementEnrichmentFailure> indexed(
            List<LhAnnouncementEnrichmentFailure> failures
    ) {
        Map<FailureKey, LhAnnouncementEnrichmentFailure> indexed = new LinkedHashMap<>();
        failures.forEach(failure -> indexed.put(FailureKey.from(failure), failure));
        return indexed;
    }

    private record FailureKey(String sourceKey, Object reason) {

        private static FailureKey from(LhAnnouncementEnrichmentFailure failure) {
            return new FailureKey(failure.getSourceKey(), failure.getReason());
        }
    }
}
