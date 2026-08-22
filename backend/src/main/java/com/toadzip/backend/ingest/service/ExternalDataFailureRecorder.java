package com.toadzip.backend.ingest.service;

import java.time.Clock;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.repository.ExternalDataCollectionStore;

@Service
public class ExternalDataFailureRecorder {

    private static final int MAX_REASON_LENGTH = 1_000;

    private final Clock clock;

    private final ExternalDataCollectionStore store;

    public ExternalDataFailureRecorder(Clock clock, ExternalDataCollectionStore store) {
        this.clock = clock;
        this.store = store;
    }

    public void record(
            ExternalDataSource source,
            String requestDescription,
            RuntimeException exception,
            Logger logger,
            String logMessage
    ) {
        String reason = reasonOf(exception);
        store.storeFailure(ExternalDataCollectionFailure.create(
                source,
                requestDescription,
                clock.instant(),
                exception.getClass().getSimpleName(),
                reason
        ));
        logger.warn(logMessage + ": request={}, reason={}", requestDescription, reason, exception);
    }

    private String reasonOf(RuntimeException exception) {
        String reason = exception.getMessage();
        if (reason == null || reason.isBlank()) {
            return "외부 데이터 수집 중 원인을 확인할 수 없는 오류가 발생했습니다.";
        }
        String singleLine = reason.replaceAll("[\\r\\n]+", " ");
        if (singleLine.length() <= MAX_REASON_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_REASON_LENGTH);
    }
}
