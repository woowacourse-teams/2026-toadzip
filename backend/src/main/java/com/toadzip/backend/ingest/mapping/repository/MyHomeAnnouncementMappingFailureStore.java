package com.toadzip.backend.ingest.mapping.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.failure.domain.IngestFailureReconciler;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class MyHomeAnnouncementMappingFailureStore {

    private final MyHomeAnnouncementMappingFailureRepository repository;
    private final Clock clock;

    @Transactional
    public void reconcileForAnnouncement(
            String identifier, List<MyHomeAnnouncementMappingFailure> failures, UUID executionId
    ) {
        if (identifier == null || identifier.isBlank() || failures.stream()
                .anyMatch(failure -> !identifier.equals(failure.getSourceAnnouncementIdentifier()))) {
            throw new IllegalArgumentException("재검사한 공고에 속한 실패만 갱신할 수 있습니다.");
        }
        List<MyHomeAnnouncementMappingFailure> stored = repository
                .findAllBySourceAnnouncementIdentifier(identifier);
        IngestFailureReconciler.reconcile(stored, List.of(), failures, clock.instant(), executionId)
                .forEach(repository::save);
    }

    @Transactional
    public void reconcileAfterRun(List<MyHomeAnnouncementMappingFailure> failures, UUID executionId) {
        Instant resolvedAt = clock.instant();
        List<MyHomeAnnouncementMappingFailure> stored = repository.findAllByStatus(PENDING);
        List<MyHomeAnnouncementMappingFailure> history = List.of();
        if (!failures.isEmpty()) {
            history = repository.findAllBySourceKeyIn(
                    failures.stream().map(MyHomeAnnouncementMappingFailure::getSourceKey).distinct().toList()
            );
        }
        IngestFailureReconciler.reconcile(stored, history, failures, resolvedAt, executionId)
                .forEach(repository::save);
    }
}
