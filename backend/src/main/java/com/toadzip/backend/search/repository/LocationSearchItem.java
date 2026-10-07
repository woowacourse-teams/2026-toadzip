package com.toadzip.backend.search.repository;

import com.toadzip.backend.housing.domain.MapCoordinate;
import com.toadzip.backend.search.domain.LocationSearchType;

public record LocationSearchItem(
        LocationSearchType type,
        String id,
        String title,
        String subtitle,
        MapCoordinate coordinate
) {
}
