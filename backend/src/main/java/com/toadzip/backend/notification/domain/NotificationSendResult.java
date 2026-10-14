package com.toadzip.backend.notification.domain;

import java.time.Instant;

public record NotificationSendResult(Status status, String providerMessageId, String errorCode, Instant retryAfter) {
    public enum Status {
        ACCEPTED, RETRYABLE, INVALID_REGISTRATION, PERMANENT_FAILURE, SKIPPED
    }
}
