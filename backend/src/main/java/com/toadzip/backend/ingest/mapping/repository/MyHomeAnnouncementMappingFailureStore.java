package com.toadzip.backend.ingest.mapping.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.failure.service.IngestExecutionContext;
import com.toadzip.backend.ingest.mapping.domain.MyHomeAnnouncementMappingFailure;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyHomeAnnouncementMappingFailureStore {

    private final MyHomeAnnouncementMappingFailureRepository repository;
    private final Clock clock;

    public MyHomeAnnouncementMappingFailureStore(
            MyHomeAnnouncementMappingFailureRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void replaceAll(List<MyHomeAnnouncementMappingFailure> failures) {
        UUID executionId = IngestExecutionContext.currentExecutionId().orElse(null);
        Instant resolvedAt = clock.instant();
        Map<FailureKey, MyHomeAnnouncementMappingFailure> stored = indexed(repository.findAll());
        Map<FailureKey, MyHomeAnnouncementMappingFailure> observed = indexed(failures);
        stored.forEach((key, failure) -> {
            if (failure.getStatus() == PENDING && !observed.containsKey(key)) {
                failure.resolve(resolvedAt, executionId);
            }
        });
        observed.forEach((key, failure) -> {
            MyHomeAnnouncementMappingFailure existing = stored.get(key);
            if (existing == null) {
                failure.attachFirstExecution(executionId);
                repository.save(failure);
                return;
            }
            existing.observe(failure, executionId);
        });
    }

    private Map<FailureKey, MyHomeAnnouncementMappingFailure> indexed(
            List<MyHomeAnnouncementMappingFailure> failures
    ) {
        Map<FailureKey, MyHomeAnnouncementMappingFailure> indexed = new LinkedHashMap<>();
        failures.forEach(failure -> indexed.put(FailureKey.from(failure), failure));
        return indexed;
    }

    private record FailureKey(String sourceKey, Object reason) {

        private static FailureKey from(MyHomeAnnouncementMappingFailure failure) {
            return new FailureKey(failure.getSourceKey(), failure.getReason());
        }
    }
}
