package com.toadzip.backend.interest.dto;

import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import java.time.Instant;
import java.util.UUID;

public record NotificationSettingsResponse(
        UUID eventId,
        NotificationTargetType targetType,
        String targetId,
        NotificationInterestOutcome outcome,
        Instant occurredAt,
        long settingsRevision,
        CurrentTarget currentTarget
) {
    public record CurrentTarget(boolean active, Instant expiresAt, String noticeVersion, Instant requestedAt) {
    }
}
