package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.MyHomeComplexMappingFailure;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyHomeComplexMappingFailureStore {

    private final MyHomeComplexMappingFailureRepository repository;

    public MyHomeComplexMappingFailureStore(MyHomeComplexMappingFailureRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void replaceAll(List<MyHomeComplexMappingFailure> failures) {
        repository.deleteAllInBatch();
        repository.saveAll(failures);
    }

    @Transactional
    public void replaceForComplex(
            String sourceComplexIdentifier,
            List<MyHomeComplexMappingFailure> failures
    ) {
        repository.deleteAllBySourceComplexIdentifier(sourceComplexIdentifier);
        repository.saveAll(failures);
    }
}
