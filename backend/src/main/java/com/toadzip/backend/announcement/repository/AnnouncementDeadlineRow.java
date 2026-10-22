package com.toadzip.backend.announcement.repository;

import com.toadzip.backend.announcement.domain.Announcement;
import java.time.LocalDate;

public record AnnouncementDeadlineRow(Announcement announcement, int priority, LocalDate sortDate) {
}
