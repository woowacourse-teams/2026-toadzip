package com.toadzip.backend.search.dto.response;

import com.toadzip.backend.search.domain.SearchType;
import java.math.BigDecimal;
import java.time.LocalDate;

public record SearchResultItemResponse(
        SearchType type,
        String id,
        String title,
        String subtitle,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalDate publishedAt,
        String applicationStatus,
        String regionCode
) {
}
