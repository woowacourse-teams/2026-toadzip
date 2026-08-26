package com.toadzip.backend.ingest.service;

import java.time.Duration;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.repository.external.ExternalDataRequestException;

@Slf4j
@Component
public class ExternalDataRetryExecutor {

    private static final int MAX_ATTEMPTS = 3;

    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);

    private final Duration retryDelay;

    public ExternalDataRetryExecutor() {
        this(DEFAULT_RETRY_DELAY);
    }

    ExternalDataRetryExecutor(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public <T> T execute(
            ExternalDataSource source,
            String requestDescription,
            Supplier<T> action,
            ExternalDataCallCounter callCounter
    ) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            callCounter.increment();
            try {
                return action.get();
            }
            catch (ExternalDataRequestException exception) {
                if (!canRetry(exception, attempt)) {
                    throw new ExternalDataCallFailureException(
                            source,
                            requestDescription,
                            attempt,
                            exception
                    );
                }
                log.warn(
                        "외부 데이터 호출을 재시도합니다: source={}, request={}, attempt={}, maxAttempts={}",
                        source,
                        requestDescription,
                        attempt,
                        MAX_ATTEMPTS
                );
                waitBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("외부 API 재시도 흐름이 올바르게 종료되지 않았습니다.");
    }

    private boolean canRetry(ExternalDataRequestException exception, int attempt) {
        return exception.isRetryable() && attempt < MAX_ATTEMPTS;
    }

    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(retryDelay.multipliedBy(attempt));
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("외부 API 재시도 대기가 중단되었습니다.", exception);
        }
    }
}
