package com.toadzip.backend.search.dto.response;

import java.util.List;

public record LocationSearchResponse(
        List<LocationSearchItemResponse> items, int page, int size, boolean hasNext, Long totalCount
) {
    public LocationSearchResponse {
        items = List.copyOf(items);
    }
}
