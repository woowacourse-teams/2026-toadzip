package com.toadzip.backend.ingest.correction.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record IngestWorkspacePage(List<Item> items, Map<String, Long> counts, int page,
        long totalElements, boolean hasNext) {
    public record Item(String identifier, String name, Long productId, String status, String detail,
            Instant collectedAt) {
    }
}
