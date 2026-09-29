package com.toadzip.backend.ingest.enrichment.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.enrichment.domain.LhHouseholdEnrichmentFailure;
import com.toadzip.backend.ingest.failure.domain.IngestFailureReconciler;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class LhHouseholdEnrichmentFailureStore {

    private final LhHouseholdEnrichmentFailureRepository repository;
    private final Clock clock;

    @Transactional
    public void replaceAll(List<LhHouseholdEnrichmentFailure> failures, UUID executionId) {
        Instant resolvedAt = clock.instant();
        List<LhHouseholdEnrichmentFailure> stored = repository.findAllByStatus(PENDING);
        List<LhHouseholdEnrichmentFailure> history = List.of();
        if (!failures.isEmpty()) {
            history = repository.findAllBySourceKeyIn(
                    failures.stream().map(LhHouseholdEnrichmentFailure::getSourceKey).distinct().toList()
            );
        }
        IngestFailureReconciler.reconcile(stored, history, failures, resolvedAt, executionId)
                .forEach(repository::save);
    }
}
