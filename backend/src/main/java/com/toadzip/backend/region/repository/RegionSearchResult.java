package com.toadzip.backend.region.repository;

public record RegionSearchResult(
        String regionCode,
        String provinceName,
        String districtName,
        String displayName
) {
}
