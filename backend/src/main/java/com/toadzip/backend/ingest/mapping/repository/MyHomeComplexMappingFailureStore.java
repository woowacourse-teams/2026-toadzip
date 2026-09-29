package com.toadzip.backend.ingest.mapping.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.failure.domain.IngestFailureReconciler;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import java.time.Clock;
import java.util.EnumSet;
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
    public void replacePreparationFailures(
            List<MyHomeComplexMappingFailure> failures,
            UUID executionId
    ) {
        var preparationReasons = EnumSet.of(
                MyHomeComplexMappingFailureReason.MISSING_REQUIRED_VALUE,
                MyHomeComplexMappingFailureReason.INVALID_VALUE,
                MyHomeComplexMappingFailureReason.CONFLICTING_SOURCE_VALUE
        );
        List<MyHomeComplexMappingFailure> stored = repository.findAllByReasonInAndStatus(preparationReasons, PENDING);
        List<MyHomeComplexMappingFailure> history = List.of();
        if (!failures.isEmpty()) {
            history = repository.findAllByReasonInAndSourceKeyIn(
                    preparationReasons,
                    failures.stream()
                            .map(MyHomeComplexMappingFailure::getSourceKey)
                            .distinct()
                            .toList()
            );
        }
        reconcile(stored, history, failures, executionId);
    }

    @Transactional
    public void replaceForComplex(
            String sourceComplexIdentifier,
            List<MyHomeComplexMappingFailure> failures,
            UUID executionId
    ) {
        reconcile(
                repository.findAllBySourceComplexIdentifier(sourceComplexIdentifier),
                List.of(),
                failures,
                executionId
        );
    }

    private void reconcile(
            List<MyHomeComplexMappingFailure> storedFailures,
            List<MyHomeComplexMappingFailure> historicalFailures,
            List<MyHomeComplexMappingFailure> observedFailures,
            UUID executionId
    ) {
        IngestFailureReconciler.reconcile(
                storedFailures, historicalFailures, observedFailures, clock.instant(), executionId
        ).forEach(repository::save);
    }
}
