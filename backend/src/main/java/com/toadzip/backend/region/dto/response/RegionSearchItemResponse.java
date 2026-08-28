package com.toadzip.backend.region.dto.response;

public record RegionSearchItemResponse(
        String regionCode,
        String provinceName,
        String districtName,
        String displayName
) {
}
