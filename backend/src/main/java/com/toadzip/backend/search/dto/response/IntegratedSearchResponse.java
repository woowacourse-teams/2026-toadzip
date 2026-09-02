package com.toadzip.backend.search.dto.response;

import java.util.List;

public record IntegratedSearchResponse(
        String query,
        List<SearchResultItemResponse> housingInformation,
        List<SearchResultItemResponse> locations,
        List<SearchFailureResponse> failures,
        int page,
        int size,
        boolean hasNext
) {
    public IntegratedSearchResponse {
        housingInformation = List.copyOf(housingInformation);
        locations = List.copyOf(locations);
        failures = List.copyOf(failures);
    }
}
