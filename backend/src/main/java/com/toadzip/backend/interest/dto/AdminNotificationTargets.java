package com.toadzip.backend.interest.dto;

import com.toadzip.backend.interest.domain.NotificationTargetType;
import java.time.Instant;
import java.util.List;

public record AdminNotificationTargets(
        List<Target> items, int page, long totalElements, int totalPages, Instant asOf) {
    public record Target(NotificationTargetType targetType, String targetId, String targetName, long activeMembers) {
    }
}
