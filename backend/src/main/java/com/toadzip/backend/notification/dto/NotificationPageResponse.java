package com.toadzip.backend.notification.dto;

import java.time.Instant;
import java.util.List;

public record NotificationPageResponse(List<Item> items, String nextCursor, String readThrough) {
    public NotificationPageResponse {
        items = List.copyOf(items);
    }

    public record Item(String id, String type, String announcementId, String title, Instant createdAt,
            Instant readAt, boolean targetAvailable) {
    }
}
