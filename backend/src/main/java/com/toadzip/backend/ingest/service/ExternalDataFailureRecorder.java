package com.toadzip.backend.ingest.service;

import java.time.Clock;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.repository.ExternalDataFailureStore;

@Service
public class ExternalDataFailureRecorder {

    private static final int MAX_REASON_LENGTH = 1_000;

    private final Clock clock;

    private final ExternalDataFailureStore store;

    public ExternalDataFailureRecorder(Clock clock, ExternalDataFailureStore store) {
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
        FailureDetails details = detailsOf(exception, requestDescription);
        store.store(ExternalDataCollectionFailure.create(
                source,
                details.requestDescription(),
                clock.instant(),
                details.attemptCount(),
                details.exception().getClass().getSimpleName(),
                reasonOf(details.exception())
        ));
        logger.warn(
                logMessage + ": request={}, attemptCount={}, reason={}",
                details.requestDescription(),
                details.attemptCount(),
                reasonOf(details.exception()),
                exception
        );
    }

    public void resolve(ExternalDataSource source, String requestDescription) {
        store.resolve(source, requestDescription, clock.instant());
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

    private FailureDetails detailsOf(RuntimeException exception, String fallbackRequestDescription) {
        if (exception instanceof ExternalDataCallFailureException callFailure) {
            RuntimeException cause = (RuntimeException) callFailure.getCause();
            return new FailureDetails(
                    callFailure.getRequestDescription(),
                    callFailure.getAttemptCount(),
                    cause
            );
        }
        return new FailureDetails(fallbackRequestDescription, 0, exception);
    }

    private record FailureDetails(String requestDescription, int attemptCount, RuntimeException exception) {
    }
}
