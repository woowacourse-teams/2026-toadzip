package com.toadzip.backend.notification.dto;

import java.time.Instant;

public record NotificationPushDeviceSummaryResponse(String deviceId, String bindingVersion, String platform,
        String browser, Instant lastSeenAt, boolean enabled, boolean resumeAfterLogin, boolean current) {
}
