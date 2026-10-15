package com.toadzip.backend.notification.dto;

import java.time.Instant;
import com.toadzip.backend.notification.domain.NotificationContent;
import java.util.List;

public record NotificationPageResponse(List<Item> items, String nextCursor, String readThrough, long totalCount) {
    public NotificationPageResponse {
        items = List.copyOf(items);
    }

    public record Item(String id, String type, String announcementId, String title, Instant createdAt,
            Instant readAt, boolean targetAvailable, NotificationContent content) {
    }
}
