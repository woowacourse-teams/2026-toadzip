package com.toadzip.backend.notification.dto;

import java.time.Instant;

public record AdminNotificationDelivery(long notificationId, long userId, long announcementId, String title,
        String deliveryStatus, Long deliveryId, int attempts, String errorCode, Instant createdAt,
        Instant nextAttemptAt, Instant finishedAt, Instant readAt, boolean deleted, Instant expiresAt) { }
