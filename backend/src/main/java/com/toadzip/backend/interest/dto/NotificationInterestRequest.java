package com.toadzip.backend.interest.dto;

import com.toadzip.backend.interest.domain.NotificationEventSource;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record NotificationInterestRequest(
        @NotNull UUID eventId,
        @NotNull UUID sessionId,
        @NotNull NotificationEventType eventType,
        @NotNull NotificationEventSource source,
        @NotNull NotificationTargetType targetType,
        @NotNull @Pattern(regexp = "[0-9]{1,19}") String targetId,
        @Email @Size(max = 254) String email,
        UUID clientId
) {
}
