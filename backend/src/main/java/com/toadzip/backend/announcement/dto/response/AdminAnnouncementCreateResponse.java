package com.toadzip.backend.announcement.dto.response;

public record AdminAnnouncementCreateResponse(
        long announcementId,
        long supplyRowId,
        long housingComplexId,
        String name
) {
}
