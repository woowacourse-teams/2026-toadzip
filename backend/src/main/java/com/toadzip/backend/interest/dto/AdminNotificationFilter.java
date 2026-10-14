package com.toadzip.backend.interest.dto;

import com.toadzip.backend.interest.domain.AdminNotificationStatus;
import com.toadzip.backend.interest.domain.AdminNotificationReview;
import com.toadzip.backend.interest.domain.NotificationTargetType;

public record AdminNotificationFilter(
        String keyword, Long userId, NotificationTargetType targetType, String targetId,
        AdminNotificationStatus status, AdminNotificationReview review, boolean groupTarget) {
}
