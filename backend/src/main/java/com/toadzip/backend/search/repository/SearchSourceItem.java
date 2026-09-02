package com.toadzip.backend.search.repository;

import com.toadzip.backend.search.domain.SearchType;
import java.math.BigDecimal;
import java.time.LocalDate;

public record SearchSourceItem(
        SearchType type,
        String id,
        String title,
        String subtitle,
        String address,
        String category,
        BigDecimal latitude,
        BigDecimal longitude,
        LocalDate publishedAt,
        String applicationStatus,
        boolean cancelled,
        String regionCode
) {
}
