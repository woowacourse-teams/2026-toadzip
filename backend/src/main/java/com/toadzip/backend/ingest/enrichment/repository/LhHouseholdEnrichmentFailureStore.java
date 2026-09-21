package com.toadzip.backend.ingest.enrichment.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LhHouseholdEnrichmentFailureStore {

    private final LhHouseholdEnrichmentFailureRepository repository;
    private final Clock clock;

    public LhHouseholdEnrichmentFailureStore(
            LhHouseholdEnrichmentFailureRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void replaceAll(List<LhHouseholdEnrichmentFailure> failures, UUID executionId) {
        Instant resolvedAt = clock.instant();
        Map<FailureKey, LhHouseholdEnrichmentFailure> stored = indexed(repository.findAll());
        Map<FailureKey, LhHouseholdEnrichmentFailure> observed = indexed(failures);
        stored.forEach((key, failure) -> {
            if (failure.getStatus() == PENDING && !observed.containsKey(key)) {
                failure.resolve(resolvedAt, executionId);
            }
        });
        observed.forEach((key, failure) -> {
            LhHouseholdEnrichmentFailure existing = stored.get(key);
            if (existing == null) {
                failure.attachFirstExecution(executionId);
                repository.save(failure);
                return;
            }
            existing.observe(failure, executionId);
        });
    }

    private Map<FailureKey, LhHouseholdEnrichmentFailure> indexed(
            List<LhHouseholdEnrichmentFailure> failures
    ) {
        Map<FailureKey, LhHouseholdEnrichmentFailure> indexed = new LinkedHashMap<>();
        failures.forEach(failure -> indexed.put(FailureKey.from(failure), failure));
        return indexed;
    }

    private record FailureKey(String sourceKey, Object reason) {

        private static FailureKey from(LhHouseholdEnrichmentFailure failure) {
            return new FailureKey(failure.getSourceKey(), failure.getReason());
        }
    }
}
