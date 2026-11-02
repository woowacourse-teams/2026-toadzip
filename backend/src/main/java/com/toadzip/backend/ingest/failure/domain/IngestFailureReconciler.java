package com.toadzip.backend.ingest.failure.domain;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IngestFailureReconciler {

    private IngestFailureReconciler() {
    }

    public static <T extends IngestFailure<T>> List<T> reconcile(
            List<T> storedFailures,
            List<T> historicalFailures,
            List<T> observedFailures,
            Instant resolvedAt,
            UUID executionId
    ) {
        Map<FailureKey, T> stored = new LinkedHashMap<>();
        storedFailures.forEach(failure -> stored.put(FailureKey.from(failure), failure));
        // Supplement history without replacing the rows selected by the scoped query.
        historicalFailures.forEach(failure -> stored.putIfAbsent(FailureKey.from(failure), failure));
        Map<FailureKey, T> observed = new LinkedHashMap<>();
        observedFailures.forEach(failure -> observed.put(FailureKey.from(failure), failure));
        stored.forEach((key, failure) -> {
            if (failure.getStatus() == PENDING && !observed.containsKey(key)) {
                failure.resolve(resolvedAt, executionId);
            }
        });
        List<T> created = new ArrayList<>();
        observed.forEach((key, failure) -> {
            T existing = stored.get(key);
            if (existing == null) {
                failure.attachFirstExecution(executionId);
                created.add(failure);
                return;
            }
            existing.observe(failure, executionId);
        });
        return created;
    }

    private record FailureKey(String sourceKey, Enum<?> reason) {

        private static FailureKey from(IngestFailure<?> failure) {
            return new FailureKey(failure.getSourceKey(), failure.getReason());
        }
    }
}
