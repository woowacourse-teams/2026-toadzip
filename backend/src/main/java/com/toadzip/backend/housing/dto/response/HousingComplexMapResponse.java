package com.toadzip.backend.housing.dto.response;

import java.util.List;

public record HousingComplexMapResponse(List<HousingComplexMapItemResponse> items) {

    public HousingComplexMapResponse {
        items = List.copyOf(items);
    }
}
