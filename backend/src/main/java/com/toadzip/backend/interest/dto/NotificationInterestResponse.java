package com.toadzip.backend.interest.dto;

import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import java.time.Instant;
import java.util.UUID;

public record NotificationInterestResponse(
        UUID eventId,
        NotificationTargetType targetType,
        String targetId,
        NotificationInterestOutcome outcome,
        Instant occurredAt
) {
}
