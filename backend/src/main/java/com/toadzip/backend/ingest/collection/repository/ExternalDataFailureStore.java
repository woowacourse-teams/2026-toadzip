package com.toadzip.backend.ingest.collection.repository;

import com.toadzip.backend.ingest.collection.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.collection.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.failure.service.IngestExecutionContext;
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
        var executionId = IngestExecutionContext.currentExecutionId().orElse(null);
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
    public void resolve(ExternalDataSource source, String requestDescription, Instant resolvedAt) {
        failureRepository.findAllBySourceAndRequestDescriptionAndStatus(
                source,
                requestDescription,
                ExternalDataFailureStatus.PENDING
        ).forEach(failure -> failure.resolve(
                resolvedAt,
                IngestExecutionContext.currentExecutionId().orElse(null)
        ));
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
        ).forEach(failure -> failure.skip(
                skippedAt,
                skipReason,
                IngestExecutionContext.currentExecutionId().orElse(null)
        ));
    }
}
