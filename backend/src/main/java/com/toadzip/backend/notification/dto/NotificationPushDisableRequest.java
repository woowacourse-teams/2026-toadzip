package com.toadzip.backend.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record NotificationPushDisableRequest(
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedUserId,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String bindingVersion) {
}
