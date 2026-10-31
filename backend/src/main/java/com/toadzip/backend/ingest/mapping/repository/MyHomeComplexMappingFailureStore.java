package com.toadzip.backend.ingest.mapping.repository;

import static com.toadzip.backend.ingest.failure.domain.IngestFailureStatus.PENDING;

import com.toadzip.backend.ingest.failure.repository.IngestFailureStore;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyHomeComplexMappingFailureStore extends IngestFailureStore<MyHomeComplexMappingFailure> {

    private final MyHomeComplexMappingFailureRepository repository;

    public MyHomeComplexMappingFailureStore(MyHomeComplexMappingFailureRepository repository, Clock clock) {
        super(repository, clock);
        this.repository = repository;
    }

    @Transactional
    public void reconcileAfterRun(List<MyHomeComplexMappingFailure> failures, UUID executionId) {
        reconcileWithHistory(repository.findAllByStatusOrderBySourceKeyAsc(PENDING), failures, executionId);
    }

    @Transactional
    public void recordObserved(List<MyHomeComplexMappingFailure> failures, UUID executionId) {
        if (!failures.isEmpty()) {
            reconcileWithHistory(List.of(), failures, executionId);
        }
    }
}
