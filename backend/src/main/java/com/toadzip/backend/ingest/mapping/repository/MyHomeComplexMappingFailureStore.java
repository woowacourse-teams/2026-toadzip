package com.toadzip.backend.ingest.mapping.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.failure.domain.IngestFailureReconciler;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class MyHomeComplexMappingFailureStore {

    private final MyHomeComplexMappingFailureRepository repository;
    private final Clock clock;

    @Transactional
    public void reconcileAfterRun(List<MyHomeComplexMappingFailure> failures, UUID executionId) {
        reconcile(repository.findAllByStatusOrderBySourceKeyAsc(PENDING), failures, executionId);
    }

    @Transactional
    public void recordObserved(List<MyHomeComplexMappingFailure> failures, UUID executionId) {
        if (!failures.isEmpty()) {
            reconcile(List.of(), failures, executionId);
        }
    }

    @Transactional
    public void resolveForComplex(String identifier) {
        repository.findAllBySourceComplexIdentifierAndStatus(identifier, PENDING)
                .forEach(failure -> failure.resolve(clock.instant(), null));
    }

    private void reconcile(
            List<MyHomeComplexMappingFailure> pending,
            List<MyHomeComplexMappingFailure> failures,
            UUID executionId
    ) {
        List<MyHomeComplexMappingFailure> history = List.of();
        if (!failures.isEmpty()) {
            history = repository.findAllBySourceKeyIn(failures.stream()
                    .map(MyHomeComplexMappingFailure::getSourceKey)
                    .distinct()
                    .toList());
        }
        IngestFailureReconciler.reconcile(
                pending, history, failures, clock.instant(), executionId
        ).forEach(repository::save);
    }
}
