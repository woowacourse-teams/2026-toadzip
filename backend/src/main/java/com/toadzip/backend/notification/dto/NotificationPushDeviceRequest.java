package com.toadzip.backend.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NotificationPushDeviceRequest(@NotBlank @Size(max = 512) String fid,
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedUserId,
        @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedBindingVersion) {
}
