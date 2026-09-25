package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.exception.exception.LhAnnouncementUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class LhAnnouncementCircuitBreaker {

    private static final int FAILURE_THRESHOLD = 5;
    private static final Duration OPEN_DURATION = Duration.ofSeconds(30);

    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private int consecutiveFailures;
    private Instant openUntil;
    private boolean probeRunning;
    private long generation;
    private String rejectionMessage;

    public LhAnnouncementCircuitBreaker(Clock clock, MeterRegistry meterRegistry) {
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("ingest.lh.circuit.state", this, LhAnnouncementCircuitBreaker::state);
    }

    public <T> T execute(Supplier<T> request) {
        long requestGeneration = acquire();
        try {
            T result = request.get();
            succeeded(requestGeneration);
            return result;
        }
        catch (ExternalDataRequestException exception) {
            failed(requestGeneration, exception.isRetryable() || exception.isRateLimited(), exception.isRateLimited());
            throw exception;
        }
        catch (RuntimeException exception) {
            failed(requestGeneration, false, false);
            throw exception;
        }
    }

    private synchronized long acquire() {
        if (openUntil == null) {
            return generation;
        }
        if (clock.instant().isBefore(openUntil) || probeRunning) {
            meterRegistry.counter("ingest.lh.circuit.rejected").increment();
            throw new LhAnnouncementUnavailableException(rejectionMessage);
        }
        probeRunning = true;
        generation++;
        return generation;
    }

    private synchronized void succeeded(long requestGeneration) {
        if (requestGeneration != generation) {
            return;
        }
        consecutiveFailures = 0;
        if (probeRunning) {
            openUntil = null;
            probeRunning = false;
            generation++;
        }
    }

    private synchronized void failed(long requestGeneration, boolean transportFailure, boolean rateLimited) {
        if (requestGeneration != generation) {
            return;
        }
        if (!transportFailure) {
            succeeded(requestGeneration);
            return;
        }
        consecutiveFailures++;
        if (rateLimited || probeRunning || consecutiveFailures >= FAILURE_THRESHOLD) {
            openUntil = clock.instant().plus(OPEN_DURATION);
            probeRunning = false;
            generation++;
            rejectionMessage = "LH 공고 API 장애로 호출을 잠시 중단했습니다. 잠시 후 재실행해주세요.";
            if (rateLimited) {
                rejectionMessage = "LH 공고 API 호출 제한으로 수집을 잠시 중단했습니다. 잠시 후 재실행해주세요.";
            }
            meterRegistry.counter("ingest.lh.circuit.opened").increment();
        }
    }

    private synchronized double state() {
        if (probeRunning) {
            return 2;
        }
        if (openUntil != null) {
            return 1;
        }
        return 0;
    }
}
