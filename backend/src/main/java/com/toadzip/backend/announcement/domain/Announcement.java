package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.global.persistence.LegacyEnumVarcharJdbcType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.AgencyCodeConverter;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.domain.RentalTypeConverter;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcType;

@Getter
@Entity
@Table(
        name = "announcements",
        indexes = @Index(name = "idx_announcements_posted_date_id", columnList = "posted_date,id"),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_announcement_source_identifier",
                columnNames = "source_announcement_identifier"
        )
)
@NoArgsConstructor(access = PROTECTED)
public class Announcement {

    @Column(nullable = false)
    private boolean applicationScheduleReviewed;

    @Column(length = 2000)
    private String revisionEvidenceUrl;

    private String previousLhPanId;

    @Column(nullable = false)
    private boolean lhPanIdReviewed;

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
    @Convert(converter = AnnouncementPublicationTypeConverter.class)
    @JdbcType(LegacyEnumVarcharJdbcType.class)
    private AnnouncementPublicationType status;

    @Column(nullable = false)
    @Convert(converter = RentalTypeConverter.class)
    @JdbcType(LegacyEnumVarcharJdbcType.class)
    private RentalType supplyType;

    @Column(nullable = false)
    @Convert(converter = RecruitmentTypeConverter.class)
    @JdbcType(LegacyEnumVarcharJdbcType.class)
    private RecruitmentType recruitmentType;

    @Column(nullable = false)
    @Convert(converter = AgencyCodeConverter.class)
    @JdbcType(LegacyEnumVarcharJdbcType.class)
    private AgencyCode provider;

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

    private String lhPanId;

    @Column(nullable = false)
    private boolean lhReceptionPlaceOwned;

    @Column(nullable = false)
    private long viewCount;

    @Column(precision = 12, scale = 4)
    private BigDecimal actualCompetitionRate;

    @Column(precision = 12, scale = 4)
    private BigDecimal predictedCompetitionRate;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "name", column = @Column(name = "reception_place_name")),
            @AttributeOverride(name = "method", column = @Column(name = "reception_method")),
            @AttributeOverride(name = "address", column = @Column(name = "reception_address")),
            @AttributeOverride(name = "contact", column = @Column(name = "reception_contact")),
            @AttributeOverride(name = "url", column = @Column(name = "reception_url"))
    })
    private ReceptionPlace receptionPlace;

    private Announcement(
            String sourceAnnouncementIdentifier,
            String previousSourceAnnouncementIdentifier,
            Announcement previousAnnouncement,
            String name,
            AnnouncementPublicationType status,
            RentalType supplyType,
            RecruitmentType recruitmentType,
            AgencyCode provider,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            LocalDate winnerAnnouncementDate,
            String originalUrl,
            String correctionCancellationReason,
            long viewCount,
            BigDecimal actualCompetitionRate,
            BigDecimal predictedCompetitionRate,
            ReceptionPlace receptionPlace
    ) {
        validateNotBlank(sourceAnnouncementIdentifier, "원천 공고 식별자");
        validateNotBlank(name, "공고명");
        validateRequired(status, "공고 상태");
        validateRevisionLink(status, previousAnnouncement);
        validateRequired(supplyType, "공급유형");
        validateRequired(recruitmentType, "모집유형");
        validateRequired(provider, "공급기관");
        validateRequired(postedDate, "게시일");
        validateRequired(applicationStartDate, "접수 시작일");
        validateRequired(applicationEndDate, "접수 종료일");
        validateApplicationPeriod(applicationStartDate, applicationEndDate);
        validateRequired(winnerAnnouncementDate, "당첨자 발표일");
        validateNotBlank(originalUrl, "원문 URL");
        validateNonNegative(viewCount, "조회수");
        validateNonNegativeIfPresent(actualCompetitionRate, "실제 경쟁률");
        validateNonNegativeIfPresent(predictedCompetitionRate, "예상 경쟁률");
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
        this.actualCompetitionRate = actualCompetitionRate;
        this.predictedCompetitionRate = predictedCompetitionRate;
        this.receptionPlace = receptionPlace;
    }

    public void confirmApplicationPeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new IllegalArgumentException("접수 기간이 올바르지 않습니다.");
        }
        applicationStartDate = start;
        applicationEndDate = end;
        applicationScheduleReviewed = true;
    }

    public void confirmLhRevision(Announcement previous, String previousPanId, String correctedPanId,
            String evidenceUrl, String reason) {
        if (previous == null || previous == this || previousPanId == null || correctedPanId == null
                || previousPanId.equals(correctedPanId) || evidenceUrl == null || evidenceUrl.isBlank()) {
            throw new IllegalArgumentException("정정 공고 연결과 공식 근거가 필요합니다.");
        }
        previous.confirmLhPanId(previousPanId);
        confirmLhPanId(correctedPanId);
        previousAnnouncement = previous;
        previousSourceAnnouncementIdentifier = previous.getSourceAnnouncementIdentifier();
        previousLhPanId = previousPanId;
        revisionEvidenceUrl = evidenceUrl;
        correctionCancellationReason = reason;
        status = AnnouncementPublicationType.CORRECTION;
    }

    private void confirmLhPanId(String panId) {
        if (lhPanIdReviewed && !Objects.equals(lhPanId, panId)) {
            throw new IllegalArgumentException("확인된 LH 공고 원천을 바꿀 수 없습니다.");
        }
        lhPanId = panId;
        lhPanIdReviewed = true;
    }

    public static Announcement create(
            String sourceAnnouncementIdentifier,
            String previousSourceAnnouncementIdentifier,
            Announcement previousAnnouncement,
            String name,
            AnnouncementPublicationType status,
            RentalType supplyType,
            RecruitmentType recruitmentType,
            AgencyCode provider,
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
                null,
                null,
                receptionPlace
        );
    }

    public boolean updateFromMyHome(
            String previousSourceAnnouncementIdentifier,
            Announcement previousAnnouncement,
            String name,
            AnnouncementPublicationType status,
            RentalType supplyType,
            RecruitmentType recruitmentType,
            AgencyCode provider,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            LocalDate winnerAnnouncementDate,
            String originalUrl,
            ReceptionPlace receptionPlace
    ) {
        if (applicationScheduleReviewed) {
            applicationStartDate = this.applicationStartDate;
            applicationEndDate = this.applicationEndDate;
        }
        if (lhPanIdReviewed && provider != AgencyCode.LH) {
            throw new IllegalArgumentException("확인된 LH 공고의 기관을 바꿀 수 없습니다.");
        }
        if (revisionEvidenceUrl != null) {
            previousSourceAnnouncementIdentifier = this.previousSourceAnnouncementIdentifier;
            previousAnnouncement = this.previousAnnouncement;
            if (status != AnnouncementPublicationType.CANCELLATION) {
                status = this.status;
            }
        }
        boolean remainsLh = provider == AgencyCode.LH;
        boolean preserveLhCorrection = remainsLh && lhPanId != null;
        boolean preserveLhReceptionPlace = remainsLh && lhReceptionPlaceOwned;
        String ownedCorrectionReason = preserveLhCorrection ? correctionCancellationReason : null;
        ReceptionPlace ownedReceptionPlace = preserveLhReceptionPlace ? this.receptionPlace : receptionPlace;
        Announcement incoming = new Announcement(
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
                ownedCorrectionReason,
                viewCount,
                actualCompetitionRate,
                predictedCompetitionRate,
                ownedReceptionPlace
        );
        boolean releasesLhEnrichment = !remainsLh && (lhPanId != null || lhReceptionPlaceOwned);
        if (hasSameSourceValues(incoming) && !releasesLhEnrichment) {
            return false;
        }
        applySourceValues(incoming);
        if (releasesLhEnrichment) {
            lhPanId = null;
            lhReceptionPlaceOwned = false;
        }
        return true;
    }

    private boolean hasSameSourceValues(Announcement incoming) {
        return Objects.equals(previousSourceAnnouncementIdentifier, incoming.previousSourceAnnouncementIdentifier)
                && Objects.equals(previousAnnouncement, incoming.previousAnnouncement)
                && name.equals(incoming.name)
                && status == incoming.status
                && supplyType == incoming.supplyType
                && recruitmentType == incoming.recruitmentType
                && provider == incoming.provider
                && postedDate.equals(incoming.postedDate)
                && applicationStartDate.equals(incoming.applicationStartDate)
                && applicationEndDate.equals(incoming.applicationEndDate)
                && winnerAnnouncementDate.equals(incoming.winnerAnnouncementDate)
                && originalUrl.equals(incoming.originalUrl)
                && Objects.equals(correctionCancellationReason, incoming.correctionCancellationReason)
                && hasSameReceptionPlace(incoming.receptionPlace);
    }

    private boolean hasSameReceptionPlace(ReceptionPlace incoming) {
        if (receptionPlace == null) {
            return incoming == null;
        }
        return receptionPlace.hasSameValues(incoming);
    }

    private void applySourceValues(Announcement incoming) {
        previousSourceAnnouncementIdentifier = incoming.previousSourceAnnouncementIdentifier;
        previousAnnouncement = incoming.previousAnnouncement;
        name = incoming.name;
        status = incoming.status;
        supplyType = incoming.supplyType;
        recruitmentType = incoming.recruitmentType;
        provider = incoming.provider;
        postedDate = incoming.postedDate;
        applicationStartDate = incoming.applicationStartDate;
        applicationEndDate = incoming.applicationEndDate;
        winnerAnnouncementDate = incoming.winnerAnnouncementDate;
        originalUrl = incoming.originalUrl;
        correctionCancellationReason = incoming.correctionCancellationReason;
        receptionPlace = incoming.receptionPlace;
    }

    public boolean enrichFromLh(String panId, String correctionReason, ReceptionPlace receptionPlace) {
        if (lhPanIdReviewed && !Objects.equals(lhPanId, panId)) {
            throw new IllegalArgumentException("확인된 LH 공고의 원천을 바꿀 수 없습니다.");
        }
        if (revisionEvidenceUrl != null) {
            correctionReason = correctionCancellationReason;
        }
        String ownedCorrectionReason = correctionReason == null
                ? correctionCancellationReason
                : correctionReason;
        ReceptionPlace ownedReceptionPlace = receptionPlace == null
                ? this.receptionPlace
                : receptionPlace;
        boolean ownsReceptionPlace = receptionPlace != null || lhReceptionPlaceOwned;
        if (Objects.equals(lhPanId, panId)
                && Objects.equals(correctionCancellationReason, ownedCorrectionReason)
                && hasSameReceptionPlace(ownedReceptionPlace)
                && lhReceptionPlaceOwned == ownsReceptionPlace) {
            return false;
        }
        lhPanId = panId;
        correctionCancellationReason = ownedCorrectionReason;
        this.receptionPlace = ownedReceptionPlace;
        lhReceptionPlaceOwned = ownsReceptionPlace;
        return true;
    }

    public static Announcement create(
            String sourceAnnouncementIdentifier,
            String previousSourceAnnouncementIdentifier,
            Announcement previousAnnouncement,
            String name,
            AnnouncementPublicationType status,
            RentalType supplyType,
            RecruitmentType recruitmentType,
            AgencyCode provider,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            LocalDate winnerAnnouncementDate,
            String originalUrl,
            String correctionCancellationReason,
            long viewCount,
            BigDecimal actualCompetitionRate,
            BigDecimal predictedCompetitionRate,
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
                actualCompetitionRate,
                predictedCompetitionRate,
                receptionPlace
        );
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
        return create(
                sourceAnnouncementIdentifier,
                previousSourceAnnouncementIdentifier,
                previousAnnouncement,
                name,
                AnnouncementPublicationType.fromStoredValue(status),
                RentalType.fromStoredValue(supplyType),
                RecruitmentType.fromStoredValue(recruitmentType),
                AgencyCode.fromStoredValue(provider),
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
            BigDecimal actualCompetitionRate,
            BigDecimal predictedCompetitionRate,
            ReceptionPlace receptionPlace
    ) {
        return create(
                sourceAnnouncementIdentifier,
                previousSourceAnnouncementIdentifier,
                previousAnnouncement,
                name,
                AnnouncementPublicationType.fromStoredValue(status),
                RentalType.fromStoredValue(supplyType),
                RecruitmentType.fromStoredValue(recruitmentType),
                AgencyCode.fromStoredValue(provider),
                postedDate,
                applicationStartDate,
                applicationEndDate,
                winnerAnnouncementDate,
                originalUrl,
                correctionCancellationReason,
                viewCount,
                actualCompetitionRate,
                predictedCompetitionRate,
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

    private void validateRevisionLink(
            AnnouncementPublicationType status,
            Announcement previousAnnouncement
    ) {
        if (status == AnnouncementPublicationType.ORIGINAL && previousAnnouncement != null) {
            throw new IllegalArgumentException("원공고는 이전 공고를 참조할 수 없다.");
        }
        if (status != AnnouncementPublicationType.ORIGINAL && previousAnnouncement == null) {
            throw new IllegalArgumentException("정정·취소공고는 이전 공고가 필수다.");
        }
    }

    private void validateNonNegative(long value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }

    private void validateNonNegativeIfPresent(BigDecimal value, String fieldName) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }

    private void validateApplicationPeriod(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("접수 종료일은 접수 시작일보다 빠를 수 없다.");
        }
    }
}
