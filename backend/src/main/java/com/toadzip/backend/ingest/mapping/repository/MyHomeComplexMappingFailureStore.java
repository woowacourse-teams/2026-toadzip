package com.toadzip.backend.ingest.mapping.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailureReason;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyHomeComplexMappingFailureStore {

    private final MyHomeComplexMappingFailureRepository repository;
    private final Clock clock;

    public MyHomeComplexMappingFailureStore(
            MyHomeComplexMappingFailureRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

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
        List<MyHomeComplexMappingFailure> storedPreparationFailures = repository.findAll()
                .stream()
                .filter(failure -> preparationReasons.contains(failure.getReason()))
                .toList();
        reconcile(storedPreparationFailures, failures, executionId);
    }

    @Transactional
    public void replaceForComplex(
            String sourceComplexIdentifier,
            List<MyHomeComplexMappingFailure> failures,
            UUID executionId
    ) {
        reconcile(
                repository.findAllBySourceComplexIdentifier(sourceComplexIdentifier),
                failures,
                executionId
        );
    }

    private void reconcile(
            List<MyHomeComplexMappingFailure> storedFailures,
            List<MyHomeComplexMappingFailure> observedFailures,
            UUID executionId
    ) {
        Instant resolvedAt = clock.instant();
        Map<FailureKey, MyHomeComplexMappingFailure> stored = indexed(storedFailures);
        Map<FailureKey, MyHomeComplexMappingFailure> observed = indexed(observedFailures);
        stored.forEach((key, failure) -> {
            if (failure.getStatus() == PENDING && !observed.containsKey(key)) {
                failure.resolve(resolvedAt, executionId);
            }
        });
        observed.forEach((key, failure) -> {
            MyHomeComplexMappingFailure existing = stored.get(key);
            if (existing == null) {
                failure.attachFirstExecution(executionId);
                repository.save(failure);
                return;
            }
            existing.observe(failure, executionId);
        });
    }

    private Map<FailureKey, MyHomeComplexMappingFailure> indexed(
            List<MyHomeComplexMappingFailure> failures
    ) {
        Map<FailureKey, MyHomeComplexMappingFailure> indexed = new LinkedHashMap<>();
        failures.forEach(failure -> indexed.put(FailureKey.from(failure), failure));
        return indexed;
    }

    private record FailureKey(String sourceKey, Object reason) {

        private static FailureKey from(MyHomeComplexMappingFailure failure) {
            return new FailureKey(failure.getSourceKey(), failure.getReason());
        }
    }
}
