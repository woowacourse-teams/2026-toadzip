package com.toadzip.backend.notification.domain;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public class NewAnnouncementNotificationPolicy {
    public boolean isCandidate(Instant createdAt, LocalDate postedDate,
            AnnouncementPublicationType type, Instant activationAt) {
        if (createdAt == null || postedDate == null || activationAt == null) {
            return false;
        }
        return type == AnnouncementPublicationType.ORIGINAL
                && !createdAt.isBefore(activationAt)
                && !postedDate.isBefore(activationAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate());
    }
}
