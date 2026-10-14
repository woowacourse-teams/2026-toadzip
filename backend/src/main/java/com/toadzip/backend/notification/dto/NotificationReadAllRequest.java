package com.toadzip.backend.notification.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record NotificationReadAllRequest(@NotNull @Pattern(regexp = "0|[1-9][0-9]{0,18}") String readThrough) {
}
