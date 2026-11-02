package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestRejectedException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ExternalDataRetryExecutor {

    private static final int MAX_ATTEMPTS = 3;

    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);

    private final Duration retryDelay;
    private final MeterRegistry meterRegistry;

    @Autowired
    public ExternalDataRetryExecutor(MeterRegistry meterRegistry) {
        this(DEFAULT_RETRY_DELAY, meterRegistry);
    }

    ExternalDataRetryExecutor(Duration retryDelay, MeterRegistry meterRegistry) {
        this.retryDelay = retryDelay;
        this.meterRegistry = meterRegistry;
    }

    public <T> T execute(
            ExternalDataSource source,
            String requestDescription,
            Supplier<T> action,
            ExternalDataCallCounter callCounter
    ) {
        return execute(source, requestDescription, action, callCounter, () -> {});
    }

    /** beforeAttempt는 호출 시도와 재시도 대기 중 확인마다 실행해, 함께 수집하는 다른 작업의 취소 신호를 확인한다. */
    public <T> T execute(
            ExternalDataSource source, String requestDescription, Supplier<T> action,
            ExternalDataCallCounter callCounter, Runnable beforeAttempt
    ) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            beforeAttempt.run();
            IngestExecutionScope.verifyCanContinue();
            IngestExecutionScope.requestStarted(source.operation() + " · " + requestDescription);
            callCounter.increment();
            boolean attempted = true;
            if (attempt > 1) {
                meterRegistry.counter("ingest.external.retry", "source", source.name()).increment();
            }
            try {
                return executeAttempt(source, action);
            }
            catch (ExternalDataRequestRejectedException exception) {
                callCounter.decrement();
                attempted = false;
                throw exception;
            }
            catch (ExternalDataRequestException exception) {
                if (!canRetry(exception, attempt)) {
                    throw new ExternalDataCallFailureException(
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
                waitBeforeRetry(source, attempt, beforeAttempt);
            }
            finally {
                if (attempted) {
                    IngestExecutionScope.requestFinished();
                }
            }
        }
        throw new IllegalStateException("외부 API 재시도 흐름이 올바르게 종료되지 않았습니다.");
    }

    private <T> T executeAttempt(ExternalDataSource source, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "failed";
        try {
            T response = action.get();
            result = "completed";
            return response;
        }
        catch (ExternalDataRequestRejectedException exception) {
            result = "rejected";
            throw exception;
        }
        catch (ExternalDataRequestException exception) {
            if (exception.isRateLimited()) {
                result = "rate_limited";
            }
            throw exception;
        }
        finally {
            sample.stop(meterRegistry.timer("ingest.external.request", "source", source.name(), "result", result));
        }
    }

    private boolean canRetry(ExternalDataRequestException exception, int attempt) {
        return !exception.isRateLimited()
                && exception.isRetryable()
                && attempt < MAX_ATTEMPTS;
    }

    private void waitBeforeRetry(ExternalDataSource source, int attempt, Runnable beforeAttempt) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            long deadline = System.nanoTime() + retryDelay.multipliedBy(attempt).toNanos();
            while (System.nanoTime() < deadline) {
                beforeAttempt.run();
                IngestExecutionScope.checkStopRequested();
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    Thread.sleep(Duration.ofNanos(Math.min(remaining, Duration.ofMillis(100).toNanos())));
                }
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExternalDataRetryInterruptedException(exception);
        }
        finally {
            sample.stop(meterRegistry.timer("ingest.external.retry.wait", "source", source.name()));
        }
    }
}
