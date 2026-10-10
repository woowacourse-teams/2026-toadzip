package com.toadzip.backend.interest.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.toadzip.backend.interest.domain.NotificationEventSource;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.exception.InvalidNotificationInterestException;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record NotificationSettingsRequest(
        @NotNull UUID eventId,
        @NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedUserId,
        @NotNull @PositiveOrZero Long expectedSettingsRevision,
        @NotNull NotificationEventType eventType,
        @NotNull NotificationEventSource source,
        @NotNull NotificationTargetType targetType,
        @NotNull @Pattern(regexp = "[0-9]{1,19}") String targetId,
        @Size(max = 100) String noticeVersion
) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new InvalidNotificationInterestException();
    }
}
