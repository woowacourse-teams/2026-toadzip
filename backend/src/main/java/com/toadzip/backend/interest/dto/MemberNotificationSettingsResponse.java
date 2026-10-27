package com.toadzip.backend.interest.dto;

import com.toadzip.backend.interest.domain.NotificationTargetType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record MemberNotificationSettingsResponse(String userId, long settingsRevision, List<Target> targets) {
    public record Target(NotificationTargetType targetType, String targetId, String targetName,
                         String noticeVersion, Instant requestedAt, Instant expiresAt,
                         String status, LocalDate applicationStartDate, LocalDate applicationEndDate) {
    }
}
