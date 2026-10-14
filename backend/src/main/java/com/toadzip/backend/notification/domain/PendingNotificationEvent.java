package com.toadzip.backend.notification.domain;

import java.time.Instant;

public record PendingNotificationEvent(long id, long announcementId, Instant occurredAt,
        NotificationAudienceSnapshot snapshot, long recipientCursor, Long recipientUpperBound) {
}
