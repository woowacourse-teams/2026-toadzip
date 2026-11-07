package com.toadzip.backend.eligibility.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EligibilityProcessingResponses {
    private EligibilityProcessingResponses() {}

    public record Progress(long profileVersion, long requestedVersion, String state, int failedAttempts) {}
    public record Discovery(UUID eventId, long rootAnnouncementId, long announcementId,
            List<String> optionIds, Instant discoveredAt) {}
    public record Discoveries(List<Discovery> items, long nextCursor, boolean hasMore) {}
}
