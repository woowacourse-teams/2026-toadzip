package com.toadzip.backend.ingest.repository;

import com.toadzip.backend.ingest.domain.MyHomeComplexMappingCandidate;
import java.util.Collection;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyHomeComplexMappingCandidateStore {

    private final MyHomeComplexMappingCandidateRepository repository;

    public MyHomeComplexMappingCandidateStore(MyHomeComplexMappingCandidateRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void synchronize(
            Collection<MyHomeComplexMappingCandidate> candidates,
            Collection<MyHomeComplexMappingCandidate> staleCandidates
    ) {
        repository.deleteAll(staleCandidates);
        repository.saveAll(candidates);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(MyHomeComplexMappingCandidate candidate) {
        repository.save(candidate);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(MyHomeComplexMappingCandidate candidate) {
        repository.delete(candidate);
    }

    @Transactional
    public long invalidateAll() {
        long count = repository.count();
        repository.deleteAllInBatch();
        return count;
    }
}
