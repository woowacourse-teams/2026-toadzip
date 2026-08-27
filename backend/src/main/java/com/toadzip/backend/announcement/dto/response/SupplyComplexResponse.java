package com.toadzip.backend.announcement.dto.response;

public record SupplyComplexResponse(
        long complexId,
        String name,
        String address,
        Integer totalHouseholdCount,
        String overviewImageUrl
) {
}
