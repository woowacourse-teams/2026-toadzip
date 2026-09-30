package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.housing.domain.HousingComplex;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "announcement_application_schedules")
@NoArgsConstructor(access = PROTECTED)
public class AnnouncementApplicationSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    private Announcement announcement;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "housing_complex_id")
    private HousingComplex housingComplex;

    private String supplyRank;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ApplicationScheduleState state;

    @Column(name = "application_condition", length = 1000)
    private String condition;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    private LocalTime startTime;
    private LocalTime endTime;

    @Column(nullable = false, length = 2000)
    private String sourceUrl;

    @Column(nullable = false)
    private int sourcePage;

    public static AnnouncementApplicationSchedule verified(
            Announcement announcement, HousingComplex housingComplex, String supplyRank,
            ApplicationScheduleState state, String condition,
            LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime,
            String sourceUrl, int sourcePage
    ) {
        if (announcement == null || state == null || startDate == null || endDate == null
                || endDate.isBefore(startDate) || sourcePage < 1) {
            throw new IllegalArgumentException("접수 일정과 공고문 근거가 올바르지 않습니다.");
        }
        if ((startTime == null) != (endTime == null)
                || startDate.equals(endDate) && startTime != null && endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("접수 시각은 함께 제공하고 종료는 시작보다 빠를 수 없습니다.");
        }
        if (state == ApplicationScheduleState.CONDITIONAL && (condition == null || condition.isBlank())) {
            throw new IllegalArgumentException("조건부 접수에는 진행 조건이 필요합니다.");
        }
        requireSourceUrl(sourceUrl);
        AnnouncementApplicationSchedule schedule = new AnnouncementApplicationSchedule();
        schedule.announcement = announcement;
        schedule.housingComplex = housingComplex;
        schedule.supplyRank = supplyRank;
        schedule.state = state;
        schedule.condition = condition;
        schedule.startDate = startDate;
        schedule.endDate = endDate;
        schedule.startTime = startTime;
        schedule.endTime = endTime;
        schedule.sourceUrl = sourceUrl;
        schedule.sourcePage = sourcePage;
        return schedule;
    }

    private static void requireSourceUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("공식 공고문 URL이 필요합니다.");
        }
        URI uri = URI.create(value);
        if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("공식 공고문 URL이 올바르지 않습니다.");
        }
    }

    public boolean includes(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
