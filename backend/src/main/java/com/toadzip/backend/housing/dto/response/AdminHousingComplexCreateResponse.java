package com.toadzip.backend.housing.dto.response;

public record AdminHousingComplexCreateResponse(
        long housingComplexId,
        String name,
        String roadAddress
) {
}
