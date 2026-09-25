package com.toadzip.backend.announcement.dto.response;

public record AdminAnnouncementImportCreateResponse(
        long importId,
        long announcementId,
        int supplyRowCount,
        int scheduleCount,
        int attachmentCount,
        int supplyTargetCount
) {
}
