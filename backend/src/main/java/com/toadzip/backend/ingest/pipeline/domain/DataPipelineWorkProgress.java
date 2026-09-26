package com.toadzip.backend.ingest.pipeline.domain;

import java.time.Instant;

public record DataPipelineWorkProgress(
        String label,
        String unit,
        long completedCount,
        long totalCount,
        Instant startedAt,
        Instant updatedAt
) {
}
