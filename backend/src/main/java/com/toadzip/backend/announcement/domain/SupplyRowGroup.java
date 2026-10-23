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
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "supply_row_groups")
@NoArgsConstructor(access = PROTECTED)
public class SupplyRowGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    private Announcement announcement;

    @Column(nullable = false, unique = true, length = 500)
    private String sourceRowIdentifier;

    private Integer sharedHouseholdCount;

    @Column(nullable = false, length = 64)
    private String sourceFingerprint;

    @Column(nullable = false, length = 2048)
    private String sourceUrl;

    @Column(nullable = false, length = 2000)
    private String reason;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private boolean reviewRequired;

    public static SupplyRowGroup create(Announcement announcement, String sourceRowIdentifier) {
        var group = new SupplyRowGroup();
        group.announcement = announcement;
        group.sourceRowIdentifier = sourceRowIdentifier;
        return group;
    }

    public void confirm(Integer sharedHouseholdCount, String sourceFingerprint, String sourceUrl,
            String reason, String actor) {
        if (sharedHouseholdCount != null && sharedHouseholdCount < 0) {
            throw new IllegalArgumentException("공통 모집수는 음수일 수 없습니다.");
        }
        this.sharedHouseholdCount = sharedHouseholdCount;
        this.sourceFingerprint = sourceFingerprint;
        this.sourceUrl = sourceUrl;
        this.reason = reason;
        this.actor = actor;
        this.updatedAt = Instant.now();
        this.reviewRequired = false;
    }

    public void requireReview() {
        reviewRequired = true;
    }
}
