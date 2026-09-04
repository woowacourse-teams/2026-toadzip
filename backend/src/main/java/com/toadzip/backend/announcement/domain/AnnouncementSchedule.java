package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.global.persistence.LegacyEnumVarcharJdbcType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcType;

@Getter
@Entity
@Table(name = "announcement_schedules")
@NoArgsConstructor(access = PROTECTED)
public class AnnouncementSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    private Announcement announcement;

    @Column(nullable = false)
    @Convert(converter = ScheduleTypeConverter.class)
    @JdbcType(LegacyEnumVarcharJdbcType.class)
    private ScheduleType scheduleType;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt;

    @Column(nullable = false)
    private int displayOrder;

    private String sourceScheduleIdentifier;

    private AnnouncementSchedule(
            Announcement announcement,
            ScheduleType scheduleType,
            String name,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int displayOrder
    ) {
        validateRequired(announcement, "공고");
        validateRequired(scheduleType, "일정유형");
        validateNotBlank(name, "일정명");
        validateRequired(startAt, "시작일시");
        validateRequired(endAt, "종료일시");
        validatePeriod(startAt, endAt);
        validateNonNegative(displayOrder, "표시순서");
        this.announcement = announcement;
        this.scheduleType = scheduleType;
        this.name = name;
        this.startAt = startAt;
        this.endAt = endAt;
        this.displayOrder = displayOrder;
    }

    public static AnnouncementSchedule create(
            Announcement announcement,
            ScheduleType scheduleType,
            String name,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int displayOrder
    ) {
        return new AnnouncementSchedule(announcement, scheduleType, name, startAt, endAt, displayOrder);
    }

    public static AnnouncementSchedule createFromSource(
            Announcement announcement,
            String sourceScheduleIdentifier,
            ScheduleType scheduleType,
            String name,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int displayOrder
    ) {
        AnnouncementSchedule schedule = create(announcement, scheduleType, name, startAt, endAt, displayOrder);
        schedule.sourceScheduleIdentifier = sourceScheduleIdentifier;
        return schedule;
    }

    public boolean updateFromSource(
            ScheduleType scheduleType,
            String name,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int displayOrder
    ) {
        AnnouncementSchedule incoming = create(announcement, scheduleType, name, startAt, endAt, displayOrder);
        if (this.scheduleType == incoming.scheduleType
                && this.name.equals(incoming.name)
                && this.startAt.equals(incoming.startAt)
                && this.endAt.equals(incoming.endAt)
                && this.displayOrder == incoming.displayOrder) {
            return false;
        }
        this.scheduleType = incoming.scheduleType;
        this.name = incoming.name;
        this.startAt = incoming.startAt;
        this.endAt = incoming.endAt;
        this.displayOrder = incoming.displayOrder;
        return true;
    }

    public static AnnouncementSchedule create(
            Announcement announcement,
            String scheduleType,
            String name,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int displayOrder
    ) {
        return create(announcement, ScheduleType.fromStoredValue(scheduleType), name, startAt, endAt, displayOrder);
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }

    private void validatePeriod(LocalDateTime startAt, LocalDateTime endAt) {
        if (endAt.isBefore(startAt)) {
            throw new IllegalArgumentException("일정 종료일시는 시작일시보다 빠를 수 없다.");
        }
    }
}
