package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.collection.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional
    public void skip(
            ExternalDataSource source,
            String requestDescription,
            Instant skippedAt,
            String skipReason
    ) {
        failureRepository.findAllBySourceAndRequestDescriptionAndStatus(
                source,
                requestDescription,
                ExternalDataFailureStatus.PENDING
        ).forEach(failure -> failure.skip(skippedAt, skipReason));
    }
}
