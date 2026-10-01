package com.toadzip.backend.ingest.enrichment.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
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
public class LhAnnouncementEnrichmentFailureStore {

    private final LhAnnouncementEnrichmentFailureRepository repository;
    private final Clock clock;

    @Transactional
    public void reconcileAfterRun(List<LhAnnouncementEnrichmentFailure> failures, UUID executionId) {
        Instant resolvedAt = clock.instant();
        List<LhAnnouncementEnrichmentFailure> stored = repository.findAllByStatus(PENDING);
        List<LhAnnouncementEnrichmentFailure> history = List.of();
        if (!failures.isEmpty()) {
            history = repository.findAllBySourceKeyIn(
                    failures.stream().map(LhAnnouncementEnrichmentFailure::getSourceKey).distinct().toList()
            );
        }
        IngestFailureReconciler.reconcile(stored, history, failures, resolvedAt, executionId)
                .forEach(repository::save);
    }
}
