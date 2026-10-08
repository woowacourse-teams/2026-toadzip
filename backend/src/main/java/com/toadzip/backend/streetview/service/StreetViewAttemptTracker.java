package com.toadzip.backend.streetview.service;

import com.toadzip.backend.streetview.domain.StreetViewEvent.Type;
import com.toadzip.backend.streetview.domain.StreetViewEvent;
import com.toadzip.backend.streetview.exception.StreetViewCollectionException.Reason;
import com.toadzip.backend.streetview.exception.StreetViewCollectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StreetViewAttemptTracker {
    public enum Outcome { ACCEPTED, DUPLICATE_STARTED, DUPLICATE_TERMINAL, CONFLICTING_TERMINAL }
    private final Map<UUID, Attempt> attempts = new LinkedHashMap<>();
    private final Clock clock;
    private final int capacity;
    private final Duration retention;

    public StreetViewAttemptTracker(Clock clock, @Value("${street-view.events.capacity:10000}") int capacity,
            @Value("${street-view.events.retention:PT10M}") Duration retention) {
        if (capacity <= 0 || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("거리뷰 시도 보관 제한은 양수여야 합니다.");
        }
        this.clock = clock;
        this.capacity = capacity;
        this.retention = retention;
    }

    public synchronized Outcome record(StreetViewEvent event) {
        Instant now = clock.instant();
        attempts.values().removeIf(attempt -> !now.isBefore(attempt.expiresAt));
        Attempt attempt = attempts.get(event.attemptId());
        if (attempt == null) {
            if (attempts.size() >= capacity) {
                throw new StreetViewCollectionException(Reason.CAPACITY);
            }
            attempt = new Attempt(event.complexId(), event.policyRevision(), now.plus(retention));
            attempts.put(event.attemptId(), attempt);
        }
        if (attempt.complexId != event.complexId() || attempt.policyRevision != event.policyRevision()) {
            throw new StreetViewCollectionException(Reason.ATTEMPT_CONFLICT);
        }
        return attempt.record(event);
    }

    private static final class Attempt {
        private final long complexId;
        private final long policyRevision;
        private final Instant expiresAt;
        private boolean started;
        private StreetViewEvent terminal;

        private Attempt(long complexId, long policyRevision, Instant expiresAt) {
            this.complexId = complexId;
            this.policyRevision = policyRevision;
            this.expiresAt = expiresAt;
        }

        private Outcome record(StreetViewEvent event) {
            if (event.type() == Type.STARTED) {
                if (started) {
                    return Outcome.DUPLICATE_STARTED;
                }
                started = true;
                return Outcome.ACCEPTED;
            }
            if (terminal == null) {
                terminal = event;
                return Outcome.ACCEPTED;
            }
            if (terminal.equals(event)) {
                return Outcome.DUPLICATE_TERMINAL;
            }
            return Outcome.CONFLICTING_TERMINAL;
        }
    }
}
