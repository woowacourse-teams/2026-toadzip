package com.toadzip.backend.announcement.domain;

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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 공고 공급대상: 공고문 공급표의 대상 칸 하나(신혼부부, 청년(소득 있음), 고령자 등). 같은 표기라도 신청 조건이 다르면 다른 대상이다.
 * 대상 구분이 없는 표는 '전체' 대상 하나를 둔다. 신청자격 경로가 이 대상을 가리킨다.
 */
@Getter
@Entity
@Table(name = "announcement_supply_targets", uniqueConstraints = @UniqueConstraint(name = "uk_announcement_supply_targets",
        columnNames = {"announcement_id", "label", "application_condition"}))
@NoArgsConstructor(access = PROTECTED)
public class AnnouncementSupplyTarget {

    /** 공고문 공급표에 대상 구분이 없을 때 쓰는 대상 표기. */
    public static final String EVERYONE = "전체";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Announcement announcement;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String applicationCondition;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean adminModified;

    private AnnouncementSupplyTarget(Announcement announcement, String label, String applicationCondition,
            int displayOrder) {
        if (announcement == null) {
            throw new IllegalArgumentException("공급대상의 공고는 필수다.");
        }
        this.announcement = announcement;
        this.label = label(label);
        this.applicationCondition = condition(applicationCondition);
        this.displayOrder = order(displayOrder);
    }

    public static AnnouncementSupplyTarget create(Announcement announcement, String label, String applicationCondition,
            int displayOrder) {
        return new AnnouncementSupplyTarget(announcement, label, applicationCondition, displayOrder);
    }

    /** 대상을 가리키는 자연 키. 공고 안에서 (표기, 신청 조건)이 같으면 같은 대상이다. */
    public static String key(String label, String applicationCondition) {
        return label(label) + "\u0000" + condition(applicationCondition);
    }

    public String key() {
        return key(label, applicationCondition);
    }

    public boolean everyone() {
        return EVERYONE.equals(label);
    }

    private static String label(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("공급대상 표기는 필수다.");
        }
        String stripped = value.strip();
        if (stripped.length() > 255) {
            throw new IllegalArgumentException("공급대상 표기는 255자 이하여야 한다.");
        }
        return stripped;
    }

    private static String condition(String value) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.length() > 255) {
            throw new IllegalArgumentException("신청 조건은 255자 이하여야 한다.");
        }
        return stripped;
    }

    private static int order(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("표시 순서는 음수일 수 없다.");
        }
        return value;
    }
}
