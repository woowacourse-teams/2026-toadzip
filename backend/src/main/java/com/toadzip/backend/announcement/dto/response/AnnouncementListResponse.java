package com.toadzip.backend.announcement.dto.response;

import java.util.List;

public record AnnouncementListResponse(
        List<AnnouncementListItemResponse> items,
        String nextCursor,
        boolean hasNext
) {

    public AnnouncementListResponse {
        items = List.copyOf(items);
    }
}
