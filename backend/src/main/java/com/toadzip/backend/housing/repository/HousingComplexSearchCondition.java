package com.toadzip.backend.housing.repository;

import com.toadzip.backend.housing.domain.MapBounds;

public record HousingComplexSearchCondition(
        MapBounds bounds,
        HousingComplexFilterCondition filters
) {
}
