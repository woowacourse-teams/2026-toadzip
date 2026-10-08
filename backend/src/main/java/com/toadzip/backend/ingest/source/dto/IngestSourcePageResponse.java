package com.toadzip.backend.ingest.source.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record IngestSourcePageResponse(
        List<Item> items,
        int page,
        long totalElements,
        long totalPages,
        boolean hasNext
) {
    public record Item(
            long id,
            String sourceKey,
            String name,
            String sourceUrl,
            String originalUrl,
            Instant collectedAt,
            Instant sourceUpdatedAt,
            Map<String, Object> raw
    ) {
    }
}
