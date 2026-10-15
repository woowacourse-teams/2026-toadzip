package com.toadzip.backend.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record NotificationDeviceSummary(UUID id, long bindingVersion, String platform, String browser,
        Instant lastSeenAt, boolean enabled, boolean resumeAfterLogin) {
}
