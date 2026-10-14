package com.toadzip.backend.notification.domain;

import java.time.Duration;
import java.time.Instant;

public class NotificationRetryPolicy {
    public Instant nextAttemptAt(Instant now, int attempts, Instant retryAfter, Duration jitter) {
        long delay = Math.min(3600, 60L << Math.min(6, Math.max(0, attempts - 1)));
        long jitterSeconds = Math.max(0, jitter.toSeconds());
        Instant next = now.plusSeconds(Math.min(3600, delay + Math.min(60, jitterSeconds)));
        if (retryAfter != null && retryAfter.isAfter(next)) {
            return retryAfter;
        }
        return next;
    }

    public boolean isExhausted(int attempts, Instant occurredAt, Instant now) {
        return attempts >= 8 || !now.isBefore(occurredAt.plus(Duration.ofHours(24)));
    }
}
