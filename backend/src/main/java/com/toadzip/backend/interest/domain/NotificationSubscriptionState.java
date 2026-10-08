package com.toadzip.backend.interest.domain;

import java.time.Instant;

public record NotificationSubscriptionState(boolean active, Instant expiresAt) {

    public NotificationInterestOutcome activationOutcome(Instant now) {
        if (isActiveAt(now)) {
            return NotificationInterestOutcome.ALREADY_ACTIVE;
        }
        return NotificationInterestOutcome.ACTIVATED;
    }

    public NotificationInterestOutcome cancellationOutcome(Instant now) {
        if (isActiveAt(now)) {
            return NotificationInterestOutcome.CANCELLED;
        }
        return NotificationInterestOutcome.UNCHANGED;
    }

    private boolean isActiveAt(Instant now) {
        return active && expiresAt.isAfter(now);
    }
}
