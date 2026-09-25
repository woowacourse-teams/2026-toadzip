package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.collection.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ExternalDataFailureStore {

    private final ExternalDataCollectionFailureRepository failureRepository;

    public ExternalDataFailureStore(ExternalDataCollectionFailureRepository failureRepository) {
        this.failureRepository = failureRepository;
    }

    @Transactional
    public void store(ExternalDataCollectionFailure failure, UUID executionId) {
        failureRepository.findFirstBySourceAndRequestDescriptionOrderByIdDesc(
                failure.getSource(),
                failure.getRequestDescription()
        ).ifPresentOrElse(
                stored -> stored.observe(failure, executionId),
                () -> {
                    failure.attachFirstExecution(executionId);
                    failureRepository.save(failure);
                }
        );
    }

    @Transactional
    public void resolve(
            ExternalDataSource source,
            String requestDescription,
            Instant resolvedAt,
            UUID executionId
    ) {
        failureRepository.findAllBySourceAndRequestDescriptionAndStatus(
                source,
                requestDescription,
                ExternalDataFailureStatus.PENDING
        ).forEach(failure -> failure.resolve(resolvedAt, executionId));
    }

    @Transactional
    public void resolveStartingWith(
            ExternalDataSource source,
            String requestDescriptionPrefix,
            Instant resolvedAt,
            UUID executionId
    ) {
        failureRepository.findAllBySourceAndStatusAndRequestDescriptionStartingWith(
                source,
                ExternalDataFailureStatus.PENDING,
                requestDescriptionPrefix
        ).forEach(failure -> failure.resolve(resolvedAt, executionId));
    }

    @Transactional
    public void skip(
            ExternalDataSource source,
            String requestDescription,
            Instant skippedAt,
            String skipReason,
            UUID executionId
    ) {
        failureRepository.findAllBySourceAndRequestDescriptionAndStatus(
                source,
                requestDescription,
                ExternalDataFailureStatus.PENDING
        ).forEach(failure -> failure.skip(skippedAt, skipReason, executionId));
    }
}
