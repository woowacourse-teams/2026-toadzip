package com.toadzip.backend.ingest.service;

import java.time.Clock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.repository.ExternalDataFailureStore;

@Service
public class ExternalDataFailureRecorder {

    private static final int MAX_REASON_LENGTH = 1_000;

    private static final Pattern SERVICE_KEY_PATTERN = Pattern.compile(
            "(?i)(serviceKey\\s*=\\s*)[^\\s&,]+"
    );

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
        String safeRequestDescription = redactSecrets(details.requestDescription());
        String safeReason = reasonOf(details.exception());
        store.store(ExternalDataCollectionFailure.create(
                source,
                safeRequestDescription,
                clock.instant(),
                details.attemptCount(),
                details.exception().getClass().getSimpleName(),
                safeReason
        ));
        logger.warn(
                logMessage + ": request={}, attemptCount={}, reason={}",
                safeRequestDescription,
                details.attemptCount(),
                safeReason,
                sanitizedException(details.exception(), safeReason)
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
        String singleLine = redactSecrets(reason.replaceAll("[\\r\\n]+", " "));
        if (singleLine.length() <= MAX_REASON_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_REASON_LENGTH);
    }

    private RuntimeException sanitizedException(RuntimeException exception, String safeReason) {
        RuntimeException sanitized = new RuntimeException(
                exception.getClass().getSimpleName() + ": " + safeReason
        );
        sanitized.setStackTrace(exception.getStackTrace());
        return sanitized;
    }

    private String redactSecrets(String value) {
        return SERVICE_KEY_PATTERN.matcher(value).replaceAll("$1[REDACTED]");
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
