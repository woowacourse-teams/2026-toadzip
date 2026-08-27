package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
final class AnnouncementApplicationStatusCalculator {

    ApplicationStatus calculateApplicationStatus(Announcement announcement, LocalDate today) {
        if (announcement.getStatus() == AnnouncementPublicationType.CANCELLATION) {
            return ApplicationStatus.CANCELLED;
        }
        if (today.isBefore(announcement.getApplicationStartDate())) {
            return ApplicationStatus.BEFORE_APPLICATION;
        }
        if (!today.isAfter(announcement.getApplicationEndDate())) {
            return ApplicationStatus.APPLYING;
        }
        return ApplicationStatus.CLOSED;
    }

    Integer calculateDDay(Announcement announcement, LocalDate today) {
        if (announcement.getStatus() == AnnouncementPublicationType.CANCELLATION) {
            return null;
        }
        if (today.isAfter(announcement.getApplicationEndDate())) {
            return null;
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(today, announcement.getApplicationEndDate()));
    }
}
