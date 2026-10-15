package com.toadzip.backend.notification.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ClaimedNotificationDelivery(long id, UUID leaseToken, long notificationId, UUID deviceId,
        long bindingVersion, String fid, long userId, long announcementId, int attempts,
        Instant occurredAt, NotificationAudienceSnapshot snapshot, NotificationContent content, Long groupId,
        List<ClaimedNotificationDelivery> batch) {
    public ClaimedNotificationDelivery {
        batch = List.copyOf(batch);
    }
    public List<ClaimedNotificationDelivery> members() {
        if (batch.isEmpty()) {
            return List.of(this);
        }
        return batch;
    }
    public ClaimedNotificationDelivery claimed(UUID token, long group, List<ClaimedNotificationDelivery> members) {
        return new ClaimedNotificationDelivery(id, token, notificationId, deviceId, bindingVersion, fid, userId,
                announcementId, attempts, occurredAt, snapshot, content, group, members);
    }
    public ClaimedNotificationDelivery withMembers(List<ClaimedNotificationDelivery> members) {
        long group = id;
        if (groupId != null) {
            group = groupId;
        }
        return claimed(leaseToken, group, members);
    }
}
