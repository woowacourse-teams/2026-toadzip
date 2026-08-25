package com.toadzip.backend.ingest.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.ingest.domain.ExternalApiCollectionFailure;

@Repository
public class ExternalApiFailureStore {

    private final ExternalApiCollectionFailureRepository failureRepository;

    public ExternalApiFailureStore(ExternalApiCollectionFailureRepository failureRepository) {
        this.failureRepository = failureRepository;
    }

    @Transactional
    public void store(ExternalApiCollectionFailure failure) {
        failureRepository.save(failure);
    }
}
