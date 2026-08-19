package com.toadzip.backend.notice.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
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

@Getter
@Entity
@Table(name = "notice_schedules")
@NoArgsConstructor(access = PROTECTED)
public class NoticeSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    private Notice notice;

    @Column(nullable = false)
    private String scheduleType;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt;

    @Column(nullable = false)
    private int displayOrder;

    private NoticeSchedule(
            Notice notice,
            String scheduleType,
            String name,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int displayOrder
    ) {
        validateRequired(notice, "공고");
        validateNotBlank(scheduleType, "일정유형");
        validateNotBlank(name, "일정명");
        validateRequired(startAt, "시작일시");
        validateRequired(endAt, "종료일시");
        validateNonNegative(displayOrder, "표시순서");
        this.notice = notice;
        this.scheduleType = scheduleType;
        this.name = name;
        this.startAt = startAt;
        this.endAt = endAt;
        this.displayOrder = displayOrder;
    }

    public static NoticeSchedule create(
            Notice notice,
            String scheduleType,
            String name,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int displayOrder
    ) {
        return new NoticeSchedule(notice, scheduleType, name, startAt, endAt, displayOrder);
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
}
