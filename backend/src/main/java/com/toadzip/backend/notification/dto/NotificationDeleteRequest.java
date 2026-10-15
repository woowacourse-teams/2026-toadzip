package com.toadzip.backend.notification.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

public record NotificationDeleteRequest(@Size(max = 1000) List<String> ids, String deleteThrough,
        @Size(max = 1000) List<String> excludedIds) {
}
