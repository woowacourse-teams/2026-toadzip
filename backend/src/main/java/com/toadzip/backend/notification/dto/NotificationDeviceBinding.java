package com.toadzip.backend.notification.dto;

import java.util.UUID;

public record NotificationDeviceBinding(UUID deviceId, long userId, long version) {
    public static final String SESSION_ATTRIBUTE = "notification-device-binding";
}
