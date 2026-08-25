package com.toadzip.backend.ingest.service;

import java.time.Duration;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.repository.external.ExternalApiRequestException;

@Slf4j
@Component
public class ExternalApiRetryExecutor {

    private static final int MAX_ATTEMPTS = 3;

    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);

    private final Duration retryDelay;

    public ExternalApiRetryExecutor() {
        this(DEFAULT_RETRY_DELAY);
    }

    ExternalApiRetryExecutor(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public <T> T execute(
            ExternalApi externalApi,
            String requestDescription,
            Supplier<T> action,
            ExternalApiCallCounter callCounter
    ) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            callCounter.increment();
            try {
                return action.get();
            }
            catch (ExternalApiRequestException exception) {
                if (!canRetry(exception, attempt)) {
                    throw exception;
                }
                log.warn(
                        "외부 API 호출을 재시도합니다: externalApi={}, request={}, attempt={}, maxAttempts={}",
                        externalApi,
                        requestDescription,
                        attempt,
                        MAX_ATTEMPTS
                );
                waitBeforeRetry();
            }
        }
        throw new IllegalStateException("외부 API 재시도 흐름이 올바르게 종료되지 않았습니다.");
    }

    private boolean canRetry(ExternalApiRequestException exception, int attempt) {
        return exception.isRetryable() && attempt < MAX_ATTEMPTS;
    }

    private void waitBeforeRetry() {
        try {
            Thread.sleep(retryDelay);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("외부 API 재시도 대기가 중단되었습니다.", exception);
        }
    }
}
