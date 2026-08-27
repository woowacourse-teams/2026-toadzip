package com.toadzip.backend.housing.repository;

public record CurrentAnnouncementTargetRow(
        long announcementId,
        long supplyRowId,
        long targetId,
        String target
) {
}
