package com.toadzip.backend.interest.domain;

import com.toadzip.backend.interest.exception.NotificationInterestConflictException;
import java.time.Instant;
import java.util.UUID;

public record NotificationSettingReceipt(UUID eventId, long userId, String fingerprint,
        NotificationTargetType targetType, String targetId, NotificationInterestOutcome outcome,
        Instant occurredAt, long settingsRevision) {

    public void verify(NotificationSettingCommand command) {
        if (userId != command.userId() || fingerprint == null || !fingerprint.equals(command.fingerprint())) {
            throw new NotificationInterestConflictException();
        }
    }
}
