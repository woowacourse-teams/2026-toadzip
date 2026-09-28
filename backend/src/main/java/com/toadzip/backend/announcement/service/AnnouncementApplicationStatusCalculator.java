package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import java.util.List;
import java.util.Comparator;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
final class AnnouncementApplicationStatusCalculator {

    ApplicationStatus calculateApplicationStatus(Announcement announcement,
            List<AnnouncementApplicationSchedule> schedules, LocalDate today) {
        if (!announcement.isApplicationScheduleReviewed()
                || announcement.getStatus() == AnnouncementPublicationType.CANCELLATION) {
            return calculateApplicationStatus(announcement, today);
        }
        if (schedules.stream().anyMatch(schedule -> schedule.includes(today)
                && schedule.getState() == ApplicationScheduleState.CONFIRMED)) {
            return ApplicationStatus.APPLYING;
        }
        if (schedules.stream().anyMatch(schedule -> schedule.includes(today))) {
            return ApplicationStatus.CONDITIONAL;
        }
        if (schedules.stream().anyMatch(schedule -> schedule.getStartDate().isAfter(today))) {
            return ApplicationStatus.BEFORE_APPLICATION;
        }
        return ApplicationStatus.CLOSED;
    }

    Integer calculateDDay(Announcement announcement, List<AnnouncementApplicationSchedule> schedules, LocalDate today) {
        if (!announcement.isApplicationScheduleReviewed()) {
            return calculateDDay(announcement, today);
        }
        if (announcement.getStatus() == AnnouncementPublicationType.CANCELLATION
                || calculateApplicationStatus(announcement, schedules, today) == ApplicationStatus.CONDITIONAL) {
            return null;
        }
        if (calculateApplicationStatus(announcement, schedules, today) == ApplicationStatus.BEFORE_APPLICATION) {
            return schedules.stream().filter(schedule -> schedule.getStartDate().isAfter(today))
                    .min(Comparator.comparing(AnnouncementApplicationSchedule::getStartDate)
                            .thenComparing(schedule -> schedule.getState() != ApplicationScheduleState.CONFIRMED)
                            .thenComparing(AnnouncementApplicationSchedule::getEndDate))
                    .filter(schedule -> schedule.getState() == ApplicationScheduleState.CONFIRMED)
                    .map(schedule -> Math.toIntExact(ChronoUnit.DAYS.between(today, schedule.getStartDate())))
                    .orElse(null);
        }
        return schedules.stream().filter(schedule -> schedule.getState() == ApplicationScheduleState.CONFIRMED)
                .filter(schedule -> !schedule.getEndDate().isBefore(today))
                .map(AnnouncementApplicationSchedule::getEndDate).min(LocalDate::compareTo)
                .map(end -> Math.toIntExact(ChronoUnit.DAYS.between(today, end))).orElse(null);
    }

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
        if (today.isBefore(announcement.getApplicationStartDate())) {
            return Math.toIntExact(ChronoUnit.DAYS.between(today, announcement.getApplicationStartDate()));
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(today, announcement.getApplicationEndDate()));
    }
}
