package com.toadzip.backend.housing.dto.response;

import java.util.List;

public record HousingComplexListResponse(
        List<HousingComplexListItemResponse> items,
        String nextCursor,
        boolean hasNext
) {
    public HousingComplexListResponse {
        items = List.copyOf(items);
    }
}
