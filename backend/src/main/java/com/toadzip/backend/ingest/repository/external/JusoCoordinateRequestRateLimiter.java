package com.toadzip.backend.ingest.repository.external;

import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

import com.toadzip.backend.ingest.exception.exception.RoadAddressGeocodingFailureReason;

@Component
public class JusoCoordinateRequestRateLimiter {

    private static final long REQUEST_INTERVAL_NANOS = Duration.ofMillis(500).toNanos();

    private final LongSupplier nanoTime;

    private final Sleeper sleeper;

    private long nextRequestNanos;

    public JusoCoordinateRequestRateLimiter() {
        this(System::nanoTime, Thread::sleep);
    }

    JusoCoordinateRequestRateLimiter(LongSupplier nanoTime, Sleeper sleeper) {
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    public synchronized void acquire() {
        long currentNanos = nanoTime.getAsLong();
        waitUntilAvailable(currentNanos);
        long availableNanos = nanoTime.getAsLong();
        nextRequestNanos = Math.max(availableNanos, nextRequestNanos) + REQUEST_INTERVAL_NANOS;
    }

    private void waitUntilAvailable(long currentNanos) {
        long waitNanos = nextRequestNanos - currentNanos;
        if (waitNanos <= 0) {
            return;
        }
        try {
            sleeper.sleep(Duration.ofNanos(waitNanos));
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JusoApiException(
                    RoadAddressGeocodingFailureReason.EXTERNAL_API_ERROR,
                    "좌표 API 호출 제한 대기가 중단되었습니다.",
                    exception
            );
        }
    }

    @FunctionalInterface
    interface Sleeper {

        void sleep(Duration duration) throws InterruptedException;
    }
}
