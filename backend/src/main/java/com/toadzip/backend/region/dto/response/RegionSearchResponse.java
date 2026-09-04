package com.toadzip.backend.region.dto.response;

import java.util.List;

public record RegionSearchResponse(List<RegionSearchItemResponse> items) {

    public RegionSearchResponse {
        items = List.copyOf(items);
    }
}
