package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.exception.exception.ExternalDataRetryInterruptedException;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementUnavailableException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
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

    private <T> T execute(
            ExternalDataSource source, String requestDescription, Supplier<T> action,
            ExternalDataCallCounter callCounter, Runnable beforeAttempt
    ) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            beforeAttempt.run();
            IngestExecutionScope.verifyHeld();
            IngestExecutionScope.checkStopRequested();
            IngestExecutionScope.requestStarted(source.operation() + " · " + requestDescription);
            callCounter.increment();
            boolean attempted = true;
            if (attempt > 1) {
                meterRegistry.counter("ingest.external.retry", "source", source.name()).increment();
            }
            try {
                return executeAttempt(source, action);
            }
            catch (LhAnnouncementUnavailableException exception) {
                callCounter.decrement();
                attempted = false;
                throw exception;
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

    /** 전체 페이지 검증과 저장은 재시도하지 않고, 실패한 실제 페이지의 호출 정보를 보존한다. */
    public <T, R> R collectPages(
            ExternalDataSource source, IntFunction<String> description, ExternalDataCallCounter counter,
            IntFunction<T> fetch, Function<IntFunction<T>, R> collection
    ) {
        return collectPages(source, description, counter, fetch, collection, () -> {});
    }

    public <T, R> R collectPages(
            ExternalDataSource source, IntFunction<String> description, ExternalDataCallCounter counter,
            IntFunction<T> fetch, Function<IntFunction<T>, R> collection, Runnable beforeAttempt
    ) {
        var lastPage = new AtomicInteger(1);
        var attempts = new AtomicInteger();
        try {
            return collection.apply(page -> {
                lastPage.set(page);
                int before = counter.count();
                try {
                    return execute(source, description.apply(page), () -> fetch.apply(page), counter, beforeAttempt);
                } finally {
                    attempts.set(counter.count() - before);
                }
            });
        } catch (IllegalArgumentException failure) {
            throw new ExternalDataCallFailureException(source, description.apply(lastPage.get()), attempts.get(),
                    new ExternalDataRequestException(failure.getMessage()));
        }
    }

    private <T> T executeAttempt(ExternalDataSource source, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "failed";
        try {
            T response = action.get();
            result = "completed";
            return response;
        }
        catch (LhAnnouncementUnavailableException exception) {
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
