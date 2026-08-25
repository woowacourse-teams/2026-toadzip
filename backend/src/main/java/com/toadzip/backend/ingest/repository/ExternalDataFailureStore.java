package com.toadzip.backend.ingest.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.domain.ExternalDataFailureStatus;

@Repository
public class ExternalDataFailureStore {

    private final ExternalDataCollectionFailureRepository failureRepository;

    public ExternalDataFailureStore(ExternalDataCollectionFailureRepository failureRepository) {
        this.failureRepository = failureRepository;
    }

    @Transactional
    public void store(ExternalDataCollectionFailure failure) {
        failureRepository.save(failure);
    }

    @Transactional
    public void resolve(ExternalDataSource source, String requestDescription, Instant resolvedAt) {
        failureRepository.findAllBySourceAndRequestDescriptionAndStatus(
                source,
                requestDescription,
                ExternalDataFailureStatus.PENDING
        ).forEach(failure -> failure.resolve(resolvedAt));
    }
}
