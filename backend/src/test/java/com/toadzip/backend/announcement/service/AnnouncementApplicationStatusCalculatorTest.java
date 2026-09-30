package com.toadzip.backend.announcement.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnnouncementApplicationStatusCalculatorTest {

    private final AnnouncementApplicationStatusCalculator calculator =
            new AnnouncementApplicationStatusCalculator();

    @Test
    void 검토된_접수일정_사이에는_다음_접수_시작일까지_계산한다() {
        Announcement announcement = announcement(AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20));
        when(announcement.isApplicationScheduleReviewed()).thenReturn(true);
        List<AnnouncementApplicationSchedule> schedules = List.of(
                schedule(announcement, ApplicationScheduleState.CONFIRMED, 1, 2),
                schedule(announcement, ApplicationScheduleState.CONFIRMED, 10, 12),
                schedule(announcement, ApplicationScheduleState.CONFIRMED, 15, 20));

        assertEquals(1, calculator.calculateDDay(announcement, schedules, LocalDate.of(2026, 8, 9)));
    }

    @Test
    void 다음_일정이_조건부이면_뒤의_확정일정으로_Dday를_만들지_않는다() {
        Announcement announcement = announcement(AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20));
        when(announcement.isApplicationScheduleReviewed()).thenReturn(true);
        List<AnnouncementApplicationSchedule> schedules = List.of(
                schedule(announcement, ApplicationScheduleState.CONDITIONAL, 10, 12),
                schedule(announcement, ApplicationScheduleState.CONFIRMED, 15, 20));

        assertNull(calculator.calculateDDay(announcement, schedules, LocalDate.of(2026, 8, 9)));
    }

    @Test
    void 같은_시작일의_확정일정이_있으면_조건부일정의_길이나_입력순서와_무관하게_시작_Dday를_표시한다() {
        Announcement announcement = announcement(AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12));
        when(announcement.isApplicationScheduleReviewed()).thenReturn(true);
        var conditional = schedule(announcement, ApplicationScheduleState.CONDITIONAL, 10, 10);
        var confirmed = schedule(announcement, ApplicationScheduleState.CONFIRMED, 10, 12);
        var sameDayConfirmed = schedule(announcement, ApplicationScheduleState.CONFIRMED, 10, 10);

        for (var schedules : List.of(List.of(conditional, confirmed), List.of(confirmed, conditional),
                List.of(conditional, sameDayConfirmed), List.of(sameDayConfirmed, conditional))) {
            assertEquals(1, calculator.calculateDDay(announcement, schedules, LocalDate.of(2026, 8, 9)));
        }
    }

    private AnnouncementApplicationSchedule schedule(Announcement announcement,
            ApplicationScheduleState state, int startDay, int endDay) {
        return AnnouncementApplicationSchedule.verified(announcement, null, "1순위", state, "잔여 세대 발생 시",
                LocalDate.of(2026, 8, startDay), LocalDate.of(2026, 8, endDay), null, null,
                "https://example.com/notice", 1);
    }

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
    void 접수시작_전에는_접수전_상태와_시작일까지_남은_날짜를_반환한다() {
        Announcement announcement = announcement(
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12)
        );

        assertEquals(
                ApplicationStatus.BEFORE_APPLICATION,
                calculator.calculateApplicationStatus(announcement, LocalDate.of(2026, 8, 9))
        );
        assertEquals(1, calculator.calculateDDay(announcement, LocalDate.of(2026, 8, 9)));
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
