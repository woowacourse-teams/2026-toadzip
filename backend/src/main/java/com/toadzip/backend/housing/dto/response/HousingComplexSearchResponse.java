package com.toadzip.backend.housing.dto.response;

import java.util.List;

public record HousingComplexSearchResponse(
        long totalCount,
        long locatedCount,
        List<Long> complexIds,
        HousingComplexSearchBoundsResponse bounds,
        List<HousingComplexMapItemResponse> mapItems,
        HousingComplexListResponse page
) {
    public HousingComplexSearchResponse {
        complexIds = List.copyOf(complexIds);
        mapItems = List.copyOf(mapItems);
    }
}
