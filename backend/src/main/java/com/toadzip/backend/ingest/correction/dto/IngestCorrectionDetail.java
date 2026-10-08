package com.toadzip.backend.ingest.correction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record IngestCorrectionDetail(String domain, String identifier, String token, Long productId,
        boolean managementOnly, Set<String> editableFields, List<Row> rows,
        BigDecimal latitude, BigDecimal longitude, List<Change> changes) {
    public record Row(String sourceKey, Map<String, Object> original, Map<String, Object> values) {
    }
    public record Change(String actor, Instant occurredAt, String beforeValue, String afterValue) {
    }
}
