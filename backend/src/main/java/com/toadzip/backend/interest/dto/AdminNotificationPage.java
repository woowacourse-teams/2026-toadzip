package com.toadzip.backend.interest.dto;

import com.toadzip.backend.interest.domain.AdminNotificationStatus;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.user.dto.AdminUserProvider;
import java.time.Instant;
import java.util.List;

public record AdminNotificationPage(
        List<Item> items, int page, boolean hasNext, long totalElements, int totalPages, Summary summary) {

    public record Item(
            String id, String userId, String email, AdminUserProvider provider,
            NotificationTargetType targetType, String targetId, String targetName,
            AdminNotificationStatus status, Instant requestedAt, Instant expiresAt) {
    }

    public record Summary(long activeMembers, long activeSubscriptions, Instant asOf) {
    }
}
