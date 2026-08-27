package com.toadzip.backend.announcement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnnouncementApplicationStatusCalculatorTest {

    private final AnnouncementApplicationStatusCalculator calculator =
            new AnnouncementApplicationStatusCalculator();

    @Test
    void 취소공고는_접수기간과_관계없이_취소상태이고_D_day가_없다() {
        Announcement announcement = announcement(
                AnnouncementPublicationType.CANCELLATION,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12)
        );

        assertEquals(
                ApplicationStatus.CANCELLED,
                calculator.calculateApplicationStatus(announcement, LocalDate.of(2026, 8, 9))
        );
        assertNull(calculator.calculateDDay(announcement, LocalDate.of(2026, 8, 9)));
    }

    @Test
    void 접수시작_전에는_접수전_상태와_마감일까지_남은_날짜를_반환한다() {
        Announcement announcement = announcement(
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12)
        );

        assertEquals(
                ApplicationStatus.BEFORE_APPLICATION,
                calculator.calculateApplicationStatus(announcement, LocalDate.of(2026, 8, 9))
        );
        assertEquals(3, calculator.calculateDDay(announcement, LocalDate.of(2026, 8, 9)));
    }

    @Test
    void 접수시작일과_종료일은_접수중_상태에_포함한다() {
        Announcement announcement = announcement(
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12)
        );

        assertEquals(
                ApplicationStatus.APPLYING,
                calculator.calculateApplicationStatus(announcement, LocalDate.of(2026, 8, 10))
        );
        assertEquals(
                ApplicationStatus.APPLYING,
                calculator.calculateApplicationStatus(announcement, LocalDate.of(2026, 8, 12))
        );
        assertEquals(0, calculator.calculateDDay(announcement, LocalDate.of(2026, 8, 12)));
    }

    @Test
    void 접수종료_후에는_마감상태이고_D_day가_없다() {
        Announcement announcement = announcement(
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12)
        );

        assertEquals(
                ApplicationStatus.CLOSED,
                calculator.calculateApplicationStatus(announcement, LocalDate.of(2026, 8, 13))
        );
        assertNull(calculator.calculateDDay(announcement, LocalDate.of(2026, 8, 13)));
    }

    private Announcement announcement(
            AnnouncementPublicationType publicationType,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate
    ) {
        Announcement announcement = mock(Announcement.class);
        when(announcement.getStatus()).thenReturn(publicationType);
        when(announcement.getApplicationStartDate()).thenReturn(applicationStartDate);
        when(announcement.getApplicationEndDate()).thenReturn(applicationEndDate);
        return announcement;
    }
}
