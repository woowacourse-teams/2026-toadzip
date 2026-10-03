package com.toadzip.backend.announcement.dto.response;

import java.util.List;

public record AnnouncementListResponse(
        List<AnnouncementListItemResponse> items,
        String nextCursor,
        boolean hasNext,
        Long totalCount
) {

    public AnnouncementListResponse(
            List<AnnouncementListItemResponse> items, String nextCursor, boolean hasNext
    ) {
        this(items, nextCursor, hasNext, null);
    }

    public AnnouncementListResponse {
        items = List.copyOf(items);
    }
}
