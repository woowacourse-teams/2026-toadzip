package com.toadzip.backend.streetview.service;

import com.toadzip.backend.streetview.exception.StreetViewCollectionException.Reason;
import com.toadzip.backend.streetview.exception.StreetViewCollectionException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StreetViewEventAdmission {
    private final Clock clock;
    private final MeterRegistry meters;
    private final int requestsPerSecond;
    private final int burst;
    private double tokens;
    private long lastRefillMillis;

    public StreetViewEventAdmission(Clock clock, MeterRegistry meters,
            @Value("${street-view.events.requests-per-second:20}") int requestsPerSecond,
            @Value("${street-view.events.burst:100}") int burst) {
        if (requestsPerSecond <= 0 || burst <= 0) {
            throw new IllegalArgumentException("거리뷰 수집 제한은 양수여야 합니다.");
        }
        this.clock = clock;
        this.meters = meters;
        this.requestsPerSecond = requestsPerSecond;
        this.burst = burst;
        this.tokens = burst;
        this.lastRefillMillis = clock.millis();
    }

    public synchronized void enter() {
        long now = clock.millis();
        if (now > lastRefillMillis) {
            tokens = Math.min(burst, tokens + (now - lastRefillMillis) * requestsPerSecond / 1000.0);
            lastRefillMillis = now;
        }
        if (tokens < 1) {
            meters.counter("street_view.collection.rejected", "reason", "RATE_LIMIT").increment();
            throw new StreetViewCollectionException(Reason.RATE_LIMIT);
        }
        tokens -= 1;
    }
}
