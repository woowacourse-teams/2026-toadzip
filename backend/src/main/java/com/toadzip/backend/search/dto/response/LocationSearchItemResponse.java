package com.toadzip.backend.search.dto.response;

import com.toadzip.backend.search.domain.LocationSearchType;
import java.math.BigDecimal;

public record LocationSearchItemResponse(
        LocationSearchType type, String id, String title, String subtitle,
        BigDecimal latitude, BigDecimal longitude
) {
}
