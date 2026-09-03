package com.toadzip.backend.search.dto.response;

import java.util.List;

public record IntegratedSearchResponse(
        String query,
        List<SearchResultItemResponse> announcements,
        List<SearchResultItemResponse> complexes,
        List<SearchResultItemResponse> regions,
        List<SearchFailureResponse> failures,
        int page,
        int size,
        boolean hasNext
) {
    public IntegratedSearchResponse {
        announcements = List.copyOf(announcements);
        complexes = List.copyOf(complexes);
        regions = List.copyOf(regions);
        failures = List.copyOf(failures);
    }
}
