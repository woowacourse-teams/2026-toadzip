package com.toadzip.backend.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record ClaimedNotificationDelivery(long id, UUID leaseToken, long notificationId, UUID deviceId,
        long bindingVersion, String fid, long userId, long announcementId, int attempts,
        Instant occurredAt, NotificationAudienceSnapshot snapshot) {
}
