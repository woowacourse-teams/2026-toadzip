package com.toadzip.backend.notification.dto;

public record NotificationPushDeviceResponse(String deviceId, boolean enabled, String bindingVersion) {
}
