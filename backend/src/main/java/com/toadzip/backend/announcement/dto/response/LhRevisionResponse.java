package com.toadzip.backend.announcement.dto.response;

public record LhRevisionResponse(
        long previousAnnouncementId,
        String previousPanId,
        String correctedPanId,
        String evidenceUrl
) {
}
