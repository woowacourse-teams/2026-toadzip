package com.toadzip.backend.ingest.failure.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record IngestFailureReviewPageResponse(
        List<Item> items,
        int page,
        long totalElements,
        long totalPages,
        boolean hasNext
) {
    public record Item(
            long id,
            String category,
            String sourceKey,
            String source,
            String reason,
            String detail,
            String status,
            Instant occurredAt,
            Instant lastOccurredAt,
            int occurrenceCount,
            int recurrenceCount,
            Instant lastResolvedAt,
            UUID firstExecutionId,
            UUID lastExecutionId,
            UUID lastResolvedExecutionId,
            String sourceComplexIdentifier,
            String sourceAnnouncementIdentifier,
            String targetName,
            Map<String, Object> metadata,
            String productLinkStatus,
            Product product
    ) {
    }

    public record Product(
            long id,
            String name,
            String resourceType,
            boolean deleted,
            Boolean hasCoordinates,
            Long housingTypeCount,
            Long linkedComplexCount,
            Long supplyRowCount,
            Long applicationScheduleCount,
            Long attachmentCount,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            Boolean applicationScheduleReviewed,
            boolean publicDetailAvailable,
            boolean publicListEligible,
            List<String> listExclusionReasons
    ) {
    }
}
