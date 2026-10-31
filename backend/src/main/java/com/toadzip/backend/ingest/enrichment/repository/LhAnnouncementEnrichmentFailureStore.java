package com.toadzip.backend.ingest.enrichment.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.enrichment.domain.LhAnnouncementEnrichmentFailure;
import com.toadzip.backend.ingest.failure.repository.IngestFailureStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
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
    public void reconcileAfterRun(List<LhAnnouncementEnrichmentFailure> failures, UUID executionId) {
        reconcileWithHistory(repository.findAllByStatus(PENDING), failures, executionId);
    }

    @Transactional
    public void reconcileForAnnouncement(
            String sourceAnnouncementIdentifier,
            List<LhAnnouncementEnrichmentFailure> failures,
            UUID executionId
    ) {
        if (sourceAnnouncementIdentifier == null || sourceAnnouncementIdentifier.isBlank()) {
            throw new IllegalArgumentException("재검사한 공고 식별자는 필수입니다.");
        }
        if (failures.stream().anyMatch(failure ->
                !sourceAnnouncementIdentifier.equals(failure.getSourceAnnouncementIdentifier()))) {
            throw new IllegalArgumentException("재검사한 공고에 속한 실패만 갱신할 수 있습니다.");
        }
        reconcile(repository.findAllBySourceAnnouncementIdentifier(sourceAnnouncementIdentifier), failures,
                executionId);
    }

    @Transactional
    public void resolveByAdmin(String identifier, String actor) {
        resolvePendingByAdmin(repository.findAllBySourceAnnouncementIdentifier(identifier), actor);
    }
}
