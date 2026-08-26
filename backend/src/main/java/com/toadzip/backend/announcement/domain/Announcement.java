package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "announcements")
@NoArgsConstructor(access = PROTECTED)
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sourceAnnouncementIdentifier;

    private String previousSourceAnnouncementIdentifier;

    @OneToOne(fetch = LAZY)
    @JoinColumn(name = "previous_announcement_id", unique = true)
    private Announcement previousAnnouncement;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String supplyType;

    @Column(nullable = false)
    private String recruitmentType;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private LocalDate postedDate;

    @Column(nullable = false)
    private LocalDate applicationStartDate;

    @Column(nullable = false)
    private LocalDate applicationEndDate;

    @Column(nullable = false)
    private LocalDate winnerAnnouncementDate;

    @Column(nullable = false)
    private String originalUrl;

    private String correctionCancellationReason;

    @Column(nullable = false)
    private long viewCount;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "reception_place_name", nullable = false)),
            @AttributeOverride(name = "method", column = @Column(name = "reception_method", nullable = false)),
            @AttributeOverride(name = "address", column = @Column(name = "reception_address")),
            @AttributeOverride(
                    name = "contact",
                    column = @Column(name = "reception_contact", nullable = false)
            ),
            @AttributeOverride(name = "url", column = @Column(name = "reception_url"))
    })
    private ReceptionPlace receptionPlace;

    private Announcement(
            String sourceAnnouncementIdentifier,
            String previousSourceAnnouncementIdentifier,
            Announcement previousAnnouncement,
            String name,
            String status,
            String supplyType,
            String recruitmentType,
            String provider,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            LocalDate winnerAnnouncementDate,
            String originalUrl,
            String correctionCancellationReason,
            long viewCount,
            ReceptionPlace receptionPlace
    ) {
        validateNotBlank(sourceAnnouncementIdentifier, "원천 공고 식별자");
        validateNotBlank(name, "공고명");
        validateNotBlank(status, "공고 상태");
        validateNotBlank(supplyType, "공급유형");
        validateNotBlank(recruitmentType, "모집유형");
        validateNotBlank(provider, "공급기관");
        validateRequired(postedDate, "게시일");
        validateRequired(applicationStartDate, "접수 시작일");
        validateRequired(applicationEndDate, "접수 종료일");
        validateApplicationPeriod(applicationStartDate, applicationEndDate);
        validateRequired(winnerAnnouncementDate, "당첨자 발표일");
        validateNotBlank(originalUrl, "원문 URL");
        validateNonNegative(viewCount, "조회수");
        validateRequired(receptionPlace, "접수처");
        this.sourceAnnouncementIdentifier = sourceAnnouncementIdentifier;
        this.previousSourceAnnouncementIdentifier = previousSourceAnnouncementIdentifier;
        this.previousAnnouncement = previousAnnouncement;
        this.name = name;
        this.status = status;
        this.supplyType = supplyType;
        this.recruitmentType = recruitmentType;
        this.provider = provider;
        this.postedDate = postedDate;
        this.applicationStartDate = applicationStartDate;
        this.applicationEndDate = applicationEndDate;
        this.winnerAnnouncementDate = winnerAnnouncementDate;
        this.originalUrl = originalUrl;
        this.correctionCancellationReason = correctionCancellationReason;
        this.viewCount = viewCount;
        this.receptionPlace = receptionPlace;
    }

    public static Announcement create(
            String sourceAnnouncementIdentifier,
            String previousSourceAnnouncementIdentifier,
            Announcement previousAnnouncement,
            String name,
            String status,
            String supplyType,
            String recruitmentType,
            String provider,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            LocalDate winnerAnnouncementDate,
            String originalUrl,
            String correctionCancellationReason,
            long viewCount,
            ReceptionPlace receptionPlace
    ) {
        return new Announcement(
                sourceAnnouncementIdentifier,
                previousSourceAnnouncementIdentifier,
                previousAnnouncement,
                name,
                status,
                supplyType,
                recruitmentType,
                provider,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                winnerAnnouncementDate,
                originalUrl,
                correctionCancellationReason,
                viewCount,
                receptionPlace
        );
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateNonNegative(long value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }

    private void validateApplicationPeriod(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("접수 종료일은 접수 시작일보다 빠를 수 없다.");
        }
    }
}
