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
    public void replaceAll(List<MyHomeAnnouncementMappingFailure> failures, UUID executionId) {
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
