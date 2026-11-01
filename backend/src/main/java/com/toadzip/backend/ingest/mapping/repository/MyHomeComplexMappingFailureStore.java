package com.toadzip.backend.ingest.mapping.repository;

import com.toadzip.backend.ingest.failure.repository.IngestFailureStore;
import com.toadzip.backend.ingest.mapping.domain.MyHomeComplexMappingFailure;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyHomeComplexMappingFailureStore extends IngestFailureStore<MyHomeComplexMappingFailure> {

    public MyHomeComplexMappingFailureStore(MyHomeComplexMappingFailureRepository repository, Clock clock) {
        super(repository, clock);
    }

    @Transactional
    public void recordObserved(List<MyHomeComplexMappingFailure> failures, UUID executionId) {
        if (!failures.isEmpty()) {
            reconcileWithHistory(List.of(), failures, executionId);
        }
    }
}
