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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    /** LH 공고 정제가 새로 만든 공고의 원천 식별자 접두사. */
    public static final String LH_SOURCE_IDENTIFIER_PREFIX = "LH:";

    @org.hibernate.annotations.CreationTimestamp
    @Column(updatable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    private java.time.Instant createdAt;

    @jakarta.persistence.Version
    @org.hibernate.annotations.ColumnDefault("0")
    private long version;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean adminModified;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean adminDeleted;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean sourceReviewRequired;

    private java.time.Instant adminUpdatedAt;

    public void moveToTrash() {
        adminDeleted = true;
        adminUpdatedAt = java.time.Instant.now();
    }

    public void restore() {
        adminDeleted = false;
        adminUpdatedAt = java.time.Instant.now();
    }


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

    @Convert(converter = RentalTypeConverter.class)
    @JdbcType(LegacyEnumVarcharJdbcType.class)
    private RentalType supplyType;

    @Convert(converter = RecruitmentTypeConverter.class)
    @JdbcType(LegacyEnumVarcharJdbcType.class)
    private RecruitmentType recruitmentType;

    @Column(nullable = false)
    @Convert(converter = AgencyCodeConverter.class)
    @JdbcType(LegacyEnumVarcharJdbcType.class)
    private AgencyCode provider;

    @Column(nullable = false)
    private LocalDate postedDate;

    private LocalDate applicationStartDate;

    private LocalDate applicationEndDate;

    private LocalDate winnerAnnouncementDate;

    @Column(nullable = false)
    private String originalUrl;

    private String correctionCancellationReason;

    private String lhPanId;

    /** 이전 아티팩트가 만든 공고는 정제가 공급행을 덮지 않도록 공고문 소유로 둔다. */
    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.ColumnDefault("'ANNOUNCEMENT_DOCUMENT'")
    @Column(nullable = false, length = 30)
    private SupplyOwner supplyOwner;

    /** 공고문 소유 공고가 확정할 때 본 LH·GH 공급 원천 지문. 그 뒤 원천이 바뀌었는지 판단하는 기준이다. 한 공고는 한 기관 원천만 가진다. */
    @Column(length = 64)
    private String lhSupplyFingerprint;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean supplyRecheckRequired;

    @Column(nullable = false)
    private boolean lhReceptionPlaceOwned;

    @Column(nullable = false, updatable = false)
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

    @Enumerated(EnumType.STRING)
    @org.hibernate.annotations.ColumnDefault("'PUBLISHED'")
    @Column(nullable = false, length = 20)
    private AnnouncementReleaseStatus releaseStatus = AnnouncementReleaseStatus.PUBLISHED;

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
        supplyOwner = AnnouncementOrigin.of(sourceAnnouncementIdentifier, null) == AnnouncementOrigin.ADMIN_ENTRY
                ? SupplyOwner.ANNOUNCEMENT_DOCUMENT : SupplyOwner.LH_SOURCE;
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

    /**
     * LH 공고목록과 SH·GH 게시판에는 정정·취소공고와 원공고를 잇는 값이 없어 정제가 공고명으로 찾은 원공고에 잇는다. 이미 이어졌거나
     * 관리자가 손댄 공고는 바꾸지 않는다. 원공고가 LH 공고가 아니면 이전 LH 공고 ID는 비어 있다.
     */
    public boolean linkRevision(Announcement previous, AnnouncementPublicationType revisionType) {
        if (revisionType == AnnouncementPublicationType.ORIGINAL || previous == null || previous == this) {
            throw new IllegalArgumentException("정정·취소공고는 다른 이전 공고에만 이을 수 있습니다.");
        }
        if (previousAnnouncement != null || adminModified || adminDeleted) {
            return false;
        }
        status = revisionType;
        previousAnnouncement = previous;
        previousSourceAnnouncementIdentifier = previous.getSourceAnnouncementIdentifier();
        previousLhPanId = previous.getLhPanId();
        return true;
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

    /**
     * 원천에서 일정·유형을 다 읽지 못한 공고를 미확정으로 만든다. 원천이 읽은 일정 후보는 {@link #observeSourceSchedule}로 채운다.
     * 사용자에게 보이지 않고, 관리자가 PDF 보정에서 단지를 확인하고 필수값을 채워 공개를 요청하면 공개된다.
     */
    public static Announcement createUnconfirmed(String sourceAnnouncementIdentifier, String name, AgencyCode provider,
            LocalDate postedDate, String originalUrl) {
        Announcement announcement = new Announcement();
        announcement.validateNotBlank(sourceAnnouncementIdentifier, "원천 공고 식별자");
        announcement.validateNotBlank(name, "공고명");
        announcement.validateRequired(provider, "공급기관");
        announcement.validateRequired(postedDate, "게시일");
        announcement.validateNotBlank(originalUrl, "원문 URL");
        announcement.sourceAnnouncementIdentifier = sourceAnnouncementIdentifier;
        announcement.name = name;
        announcement.status = AnnouncementPublicationType.ORIGINAL;
        announcement.provider = provider;
        announcement.postedDate = postedDate;
        announcement.originalUrl = originalUrl;
        announcement.supplyOwner = SupplyOwner.ANNOUNCEMENT_DOCUMENT;
        announcement.releaseStatus = AnnouncementReleaseStatus.UNCONFIRMED;
        return announcement;
    }

    public boolean isPublished() {
        return releaseStatus == AnnouncementReleaseStatus.PUBLISHED;
    }

    /** 공개 공고가 반드시 가져야 하는 접수·발표 일정과 공급·모집유형을 모두 가졌는지 본다. */
    public boolean hasPublishRequirements() {
        return applicationStartDate != null && applicationEndDate != null && winnerAnnouncementDate != null
                && supplyType != null && recruitmentType != null;
    }

    /** 관리자가 단지를 확인한 미확정 공고를 공개한다. 이번에 공개됐으면 true다. */
    public boolean publish() {
        if (isPublished()) {
            return false;
        }
        if (!hasPublishRequirements()) {
            throw new IllegalStateException("접수·발표 일정과 공급·모집유형을 모두 채운 뒤 공개할 수 있습니다.");
        }
        releaseStatus = AnnouncementReleaseStatus.PUBLISHED;
        return true;
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
        if (adminModified || adminDeleted) {
            if (adminModified && !hasSameSourceValues(incoming)) {
                sourceReviewRequired = true;
            }
            return false;
        }
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

    public AnnouncementOrigin origin() {
        return AnnouncementOrigin.of(sourceAnnouncementIdentifier, lhPanId);
    }

    /**
     * 관리자가 공급정보를 고치면 공고문이 주인이 된다. 정제는 이 공고의 공급행을 원천 값으로 다시 만들지 않는다. 기준 지문은 관리자가
     * 보고 저장한 LH 원천, 곧 마지막 정제가 남긴 지문을 그대로 둔다. 저장 전에 새로 수집된 LH 변경도 다음 정제가 재확인으로 알린다.
     */
    public void takeSupplyFromDocument() {
        supplyOwner = SupplyOwner.ANNOUNCEMENT_DOCUMENT;
        supplyRecheckRequired = false;
    }

    public boolean suppliedByLh() {
        return supplyOwner == SupplyOwner.LH_SOURCE;
    }

    /**
     * GH 공급행 틀의 원천 지문을 본다. 관리자가 공급행을 맡기 전에는 정제가 틀을 원천에 맞추므로 기준만 바꾸고, 맡은 뒤 원천이 바뀌면 공급
     * 재확인 대상으로 표시한다. 기준이 없던 공고는 이번 지문을 기준으로 남긴다. 재확인이 새로 켜졌을 때 true다.
     */
    public boolean observeAgencySupply(String fingerprint, boolean adminOwned) {
        String previous = lhSupplyFingerprint;
        lhSupplyFingerprint = fingerprint;
        if (!adminOwned || previous == null || previous.equals(fingerprint) || supplyRecheckRequired) {
            return false;
        }
        supplyRecheckRequired = true;
        return true;
    }

    /**
     * 매 정제가 이번 LH 원천 공급의 지문을 받는다. LH 소유 공고는 지문을 기준으로 남기기만 한다. 공고문 소유 공고는 기준과 다르면 재확인
     * 대상이 되고 기준을 이번 지문으로 바꾼다. 기준이 없던 공고는 이번 지문을 기준으로 남긴다. 재확인이 새로 켜졌을 때 true다.
     */
    public boolean observeLhSupply(String fingerprint) {
        if (suppliedByLh() || lhSupplyFingerprint == null || lhSupplyFingerprint.equals(fingerprint)) {
            lhSupplyFingerprint = fingerprint;
            return false;
        }
        lhSupplyFingerprint = fingerprint;
        if (supplyRecheckRequired) {
            return false;
        }
        supplyRecheckRequired = true;
        return true;
    }

    /** LH 공고목록·상세로 새 공고를 만든다. 원천 공고 식별자는 LH 공고 ID로 정하고 공급정보는 LH 원천 소유다. */
    public static Announcement createFromLh(String panId, LhAnnouncementValues values) {
        Announcement announcement = new Announcement(LH_SOURCE_IDENTIFIER_PREFIX + panId, null, null, values.name(),
                AnnouncementPublicationType.ORIGINAL, values.supplyType(), values.recruitmentType(), AgencyCode.LH,
                values.postedDate(), values.applicationStartDate(), values.applicationEndDate(),
                values.winnerAnnouncementDate(), values.originalUrl(), null, 0, null, null, null);
        announcement.lhPanId = panId;
        announcement.supplyOwner = SupplyOwner.LH_SOURCE;
        return announcement;
    }

    /**
     * LH 값으로 공고를 갱신한다. 관리자가 고친 공고는 덮지 않고 원천 값이 다르면 원천 검토 대상으로 표시한다. 휴지통 공고는 바꾸지 않는다.
     * 관리자가 확정한 접수 기간·정정 연결·공고 상태와 원천에 없는 당첨자 발표일은 그대로 둔다.
     */
    public boolean updateFromLh(LhAnnouncementValues values) {
        Announcement incoming = new Announcement(sourceAnnouncementIdentifier, previousSourceAnnouncementIdentifier,
                previousAnnouncement, values.name(), status, values.supplyType(), values.recruitmentType(),
                AgencyCode.LH, values.postedDate(), unlessReviewed(values.applicationStartDate(), applicationStartDate),
                unlessReviewed(values.applicationEndDate(), applicationEndDate),
                Objects.requireNonNullElse(values.winnerAnnouncementDate(), winnerAnnouncementDate),
                values.originalUrl(), correctionCancellationReason, viewCount, actualCompetitionRate,
                predictedCompetitionRate, receptionPlace);
        if (adminModified && !hasSameSourceValues(incoming)) {
            sourceReviewRequired = true;
        }
        if (adminModified || adminDeleted || hasSameSourceValues(incoming)) {
            return false;
        }
        applySourceValues(incoming);
        return true;
    }

    /**
     * GH·SH 원천 값으로 미확정 공고의 공고명·게시일·원문 URL을 맞춘다. 공개됐거나 관리자가 고친 공고는 덮지 않고, 원천 값이
     * 다르면 원천 검토 대상으로 표시한다. 휴지통 공고는 바꾸지 않는다. 값을 바꿨으면 true다.
     */
    public boolean updateFromAgencySource(String name, LocalDate postedDate, String originalUrl) {
        validateNotBlank(name, "공고명");
        validateRequired(postedDate, "게시일");
        validateNotBlank(originalUrl, "원문 URL");
        boolean same = this.name.equals(name) && this.postedDate.equals(postedDate)
                && this.originalUrl.equals(originalUrl);
        if (same || adminDeleted) {
            return false;
        }
        if (adminModified || isPublished()) {
            sourceReviewRequired = true;
            return false;
        }
        this.name = name;
        this.postedDate = postedDate;
        this.originalUrl = originalUrl;
        return true;
    }

    /**
     * 원천 본문에서 읽은 접수기간·당첨자 발표일 후보를 미확정 공고에 채운다. 관리자가 고치거나 일정을 확인하기 전까지는 원천이 이 값을
     * 가지므로 원천을 따라 바꾸고(읽지 못한 값은 비운다), 그 뒤나 공개된 공고는 덮지 않고 원천이 읽은 값이 다르면 원천 검토 대상으로
     * 표시한다. 휴지통 공고는 바꾸지 않는다. 시작일이 종료일보다 늦은 기간은 버린다. 값을 바꿨으면 true다.
     */
    public boolean observeSourceSchedule(LocalDate startDate, LocalDate endDate, LocalDate winnerDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            startDate = null;
            endDate = null;
        }
        boolean same = Objects.equals(applicationStartDate, startDate) && Objects.equals(applicationEndDate, endDate)
                && Objects.equals(winnerAnnouncementDate, winnerDate);
        if (same || adminDeleted) {
            return false;
        }
        if (adminModified || applicationScheduleReviewed || isPublished()) {
            // 원천이 읽지 못한 값은 관리자가 채운 값과 다르다고 보지 않는다.
            boolean readDifferently = startDate != null && !startDate.equals(applicationStartDate)
                    || endDate != null && !endDate.equals(applicationEndDate)
                    || winnerDate != null && !winnerDate.equals(winnerAnnouncementDate);
            sourceReviewRequired = sourceReviewRequired || readDifferently;
            return false;
        }
        applicationStartDate = startDate;
        applicationEndDate = endDate;
        winnerAnnouncementDate = winnerDate;
        return true;
    }

    private LocalDate unlessReviewed(LocalDate source, LocalDate current) {
        if (applicationScheduleReviewed) {
            return current;
        }
        return source;
    }

    public void recordAdminSupplyChange() {
        takeSupplyFromDocument();
        adminUpdatedAt = java.time.Instant.now();
    }

    /** 접수 일정은 공급정보가 아니므로 공급정보 소유를 바꾸지 않는다. */
    public void recordAdminScheduleChange() {
        adminUpdatedAt = java.time.Instant.now();
    }

    public void validateAdminRevision(Announcement incoming) {
        if (adminDeleted) {
            throw new IllegalArgumentException("휴지통에서 복구한 뒤 수정해 주세요.");
        }
        if (lhPanIdReviewed && incoming.provider != provider) {
            throw new IllegalArgumentException("원천 확인된 공고의 공급기관은 변경할 수 없습니다.");
        }
        if (applicationScheduleReviewed && (!applicationStartDate.equals(incoming.applicationStartDate)
                || !applicationEndDate.equals(incoming.applicationEndDate))) {
            throw new IllegalArgumentException("검증된 접수 일정은 일정 관리에서 수정해 주세요.");
        }
    }

    /** 관리자 보정 값으로 기본정보를 바꾼다. 미확정 공고의 공개는 단지를 확인한 PDF 보정에서 {@link #publish()}로 한다. */
    public void reviseByAdmin(Announcement incoming) {
        validateAdminRevision(incoming);
        name = incoming.name;
        supplyType = incoming.supplyType;
        recruitmentType = incoming.recruitmentType;
        provider = incoming.provider;
        postedDate = incoming.postedDate;
        applicationStartDate = incoming.applicationStartDate;
        applicationEndDate = incoming.applicationEndDate;
        winnerAnnouncementDate = incoming.winnerAnnouncementDate;
        originalUrl = incoming.originalUrl;
        receptionPlace = incoming.receptionPlace;
        adminModified = true;
        sourceReviewRequired = false;
        adminUpdatedAt = java.time.Instant.now();
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
                && Objects.equals(applicationStartDate, incoming.applicationStartDate)
                && Objects.equals(applicationEndDate, incoming.applicationEndDate)
                && Objects.equals(winnerAnnouncementDate, incoming.winnerAnnouncementDate)
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
        if (adminModified || adminDeleted) {
            if (adminModified && receptionPlace != null && !hasSameReceptionPlace(receptionPlace)) {
                sourceReviewRequired = true;
            }
            return false;
        }
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
