package com.toadzip.backend.ingest.quality.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record LhAnnouncementQualityResponse(
        Instant observedAt,
        Connection connection,
        Coverage amounts,
        Schedule schedules,
        Freshness supplyCollection,
        Freshness detailCollection,
        long unlinkedLhLeaseCatalogCount,
        List<UnlinkedLhCandidate> unlinkedLhCandidates,
        long preservedSourceRequestCount,
        Map<String, Long> preservedReasons,
        long preservedAmountTargetCount,
        Map<String, Long> preservedAmountReasons,
        List<HeldRequest> heldRequests
) {

    public record Coverage(long total, long fulfilled) {
    }

    public record Connection(long total, long complexLinked, long housingTypeLinked,
                             Map<String, Long> unlinkedReasons) {
    }

    public record Schedule(long total, long reviewed, long withApplicationSchedule) {
    }

    public record Freshness(long totalRequests, long freshRequests, Instant latestCollectedAt) {
    }

    public record HeldRequest(String requestDescription, String reason, Instant lastOccurredAt,
                              String proposedFingerprint) {
    }

    public record UnlinkedLhCandidate(String panId, String sourceKey, Instant changedAt) {
    }
}
