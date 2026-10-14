package com.toadzip.backend.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record NotificationDeviceState(UUID id, long userId, String fid, long bindingVersion, boolean enabled,
        Instant consentedAt) {
    public boolean retainsBinding(long user, String registration) {
        return enabled && userId == user && fid.equals(registration);
    }
}
