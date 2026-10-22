package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.YearMonth;
import java.math.BigDecimal;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "supply_rows",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_supply_row_source_identifier",
                columnNames = "source_supply_row_identifier"
        )
)
@NoArgsConstructor(access = PROTECTED)
public class SupplyRow {

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean adminModified;

    private String myhomeSourceRowIdentifier;

    public void recordMyHomeSource(String sourceIdentifier) {
        if (sourceIdentifier == null || sourceIdentifier.isBlank()) {
            throw new IllegalArgumentException("마이홈 원천행 식별자가 필요합니다.");
        }
        myhomeSourceRowIdentifier = sourceIdentifier;
    }

    public void reviseByAdmin(HousingComplex complex, HousingType type, String complexName,
            String typeName, String pnu, YearMonth moveInMonth, SupplyCategory category, Integer count) {
        if (supplyGroup != null && (count != null || complex == null || housingComplex == null
                || !complex.getId().equals(housingComplex.getId()))) {
            throw new IllegalArgumentException("공통 모집수와 단지는 원천별 공급행 보정에서 함께 변경해 주세요.");
        }
        SupplyRow incoming = create(announcement, complex, type, sourceSupplyRowIdentifier, displayOrder,
                complexName, typeName, pnu, moveInMonth, category, null, count);
        housingComplex = incoming.housingComplex;
        housingType = incoming.housingType;
        sourceComplexName = incoming.sourceComplexName;
        sourceHousingTypeName = incoming.sourceHousingTypeName;
        supplyPnu = incoming.supplyPnu;
        expectedMoveInMonth = incoming.expectedMoveInMonth;
        supplyCategory = incoming.supplyCategory;
        totalSupplyHouseholdCount = incoming.totalSupplyHouseholdCount;
        matchingFailureReason = null;
        adminModified = true;
    }

    public void protectConditionsByAdmin() {
        adminModified = true;
    }

    /** 관리자가 고친 표기값은 그대로 두고, 저장한 매칭이나 관리자 선택의 단지·주택형으로 연결만 바꾼다. */
    public boolean relinkByAdminMatch(HousingComplex complex, HousingType type) {
        if (announcement.isAdminDeleted() || supplyGroup != null) {
            return false;
        }
        if (Objects.equals(idOf(housingComplex), idOf(complex)) && Objects.equals(idOf(housingType), idOf(type))
                && matchingFailureReason == null) {
            return false;
        }
        housingComplex = complex;
        housingType = type;
        matchingFailureReason = null;
        return true;
    }

    private static Long idOf(HousingComplex complex) {
        return complex == null ? null : complex.getId();
    }

    private static Long idOf(HousingType type) {
        return type == null ? null : type.getId();
    }


    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean adminExcluded;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean areaOverride;

    @Column(precision = 10, scale = 4)
    private BigDecimal announcementExclusiveArea;

    @Column(precision = 10, scale = 4)
    private BigDecimal announcementSupplyArea;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean targetCountsComparable;

    public void confirmTargetCountScope(boolean comparable) {
        targetCountsComparable = comparable;
    }

    public void correctPresentation(boolean excluded, int order, boolean overrideArea,
            BigDecimal exclusiveArea, BigDecimal supplyArea) {
        validateNonNegative(order, "표시 순서");
        if ((exclusiveArea != null && exclusiveArea.signum() < 0)
                || (supplyArea != null && supplyArea.signum() < 0)) {
            throw new IllegalArgumentException("면적은 음수일 수 없습니다.");
        }
        adminExcluded = excluded;
        displayOrder = order;
        areaOverride = overrideArea;
        announcementExclusiveArea = exclusiveArea;
        announcementSupplyArea = supplyArea;
        adminModified = true;
    }

    public BigDecimal effectiveExclusiveArea() {
        if (areaOverride) { return announcementExclusiveArea; }
        if (housingType == null) { return null; }
        return housingType.getExclusiveArea();
    }

    public BigDecimal effectiveSupplyArea() {
        if (areaOverride) { return announcementSupplyArea; }
        if (housingType == null) { return null; }
        return housingType.getSupplyArea();
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    private Announcement announcement;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "housing_complex_id")
    private HousingComplex housingComplex;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "housing_type_id")
    private HousingType housingType;

    @Column(nullable = false)
    private String sourceSupplyRowIdentifier;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private String sourceComplexName;

    @Column(nullable = false)
    private String sourceHousingTypeName;

    @Column(nullable = false)
    private String supplyPnu;

    @Convert(converter = YearMonthAttributeConverter.class)
    private YearMonth expectedMoveInMonth;

    @Enumerated(STRING)
    @Column(nullable = false)
    private SupplyCategory supplyCategory;

    private String matchingFailureReason;

    private Integer totalSupplyHouseholdCount;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "supply_group_id")
    private SupplyRowGroup supplyGroup;

    @Column(length = 64)
    private String confirmedTargetFingerprint;

    public void protectInGroup(SupplyRowGroup group, String targetFingerprint) {
        if (!announcement.getId().equals(group.getAnnouncement().getId())) {
            throw new IllegalArgumentException("공고가 다른 공급행을 묶을 수 없습니다.");
        }
        supplyGroup = group;
        confirmedTargetFingerprint = targetFingerprint;
        totalSupplyHouseholdCount = null;
        adminModified = true;
    }

    private String lhSourceSupplyRowIdentifier;

    @Column(nullable = false)
    private boolean lhTotalSupplyHouseholdCountOwned;

    @Column(nullable = false)
    private boolean lhTotalSupplyHouseholdCountEnriched;

    private SupplyRow(
            Announcement announcement,
            HousingComplex housingComplex,
            HousingType housingType,
            String sourceSupplyRowIdentifier,
            int displayOrder,
            String sourceComplexName,
            String sourceHousingTypeName,
            String supplyPnu,
            YearMonth expectedMoveInMonth,
            SupplyCategory supplyCategory,
            String matchingFailureReason,
            Integer totalSupplyHouseholdCount
    ) {
        validateRequired(announcement, "공고");
        validateNotBlank(sourceSupplyRowIdentifier, "원천 공급행 식별자");
        validateNonNegative(displayOrder, "표시순서");
        validateNotBlank(sourceComplexName, "원천 단지명");
        validateNotBlank(sourceHousingTypeName, "원천 주택형명");
        validateNotBlank(supplyPnu, "공급 PNU");
        validateRequired(supplyCategory, "공급구분");
        validateNotBlankIfPresent(matchingFailureReason, "매칭 실패 사유");
        validateNonNegativeIfPresent(totalSupplyHouseholdCount, "전체 공급세대수");
        this.announcement = announcement;
        this.housingComplex = housingComplex;
        this.housingType = housingType;
        this.sourceSupplyRowIdentifier = sourceSupplyRowIdentifier;
        this.displayOrder = displayOrder;
        this.sourceComplexName = sourceComplexName;
        this.sourceHousingTypeName = sourceHousingTypeName;
        this.supplyPnu = supplyPnu;
        this.expectedMoveInMonth = expectedMoveInMonth;
        this.supplyCategory = supplyCategory;
        this.matchingFailureReason = matchingFailureReason;
        this.totalSupplyHouseholdCount = totalSupplyHouseholdCount;
    }

    public static SupplyRow create(
            Announcement announcement,
            HousingComplex housingComplex,
            HousingType housingType,
            String sourceSupplyRowIdentifier,
            int displayOrder,
            String sourceComplexName,
            String sourceHousingTypeName,
            String supplyPnu,
            YearMonth expectedMoveInMonth,
            SupplyCategory supplyCategory,
            String matchingFailureReason,
            Integer totalSupplyHouseholdCount
    ) {
        return new SupplyRow(
                announcement,
                housingComplex,
                housingType,
                sourceSupplyRowIdentifier,
                displayOrder,
                sourceComplexName,
                sourceHousingTypeName,
                supplyPnu,
                expectedMoveInMonth,
                supplyCategory,
                matchingFailureReason,
                totalSupplyHouseholdCount
        );
    }

    public boolean updateFromMyHome(
            HousingComplex housingComplex,
            HousingType housingType,
            int displayOrder,
            String sourceComplexName,
            String sourceHousingTypeName,
            String supplyPnu,
            SupplyCategory supplyCategory,
            String matchingFailureReason,
            Integer totalSupplyHouseholdCount
    ) {
        if (announcement.isAdminDeleted()) {
            return false;
        }
        if (adminModified) {
            return false;
        }
        boolean releasesLhEnrichment = announcement.getProvider() != null
                && announcement.getProvider() != AgencyCode.LH;
        boolean preserveLhHouseholdCount = !releasesLhEnrichment && lhTotalSupplyHouseholdCountOwned;
        YearMonth ownedExpectedMoveInMonth = releasesLhEnrichment ? null : expectedMoveInMonth;
        Integer ownedTotalSupplyHouseholdCount = preserveLhHouseholdCount
                ? this.totalSupplyHouseholdCount
                : totalSupplyHouseholdCount;
        SupplyRow incoming = new SupplyRow(
                announcement,
                housingComplex,
                housingType,
                sourceSupplyRowIdentifier,
                displayOrder,
                sourceComplexName,
                sourceHousingTypeName,
                supplyPnu,
                ownedExpectedMoveInMonth,
                supplyCategory,
                matchingFailureReason,
                ownedTotalSupplyHouseholdCount
        );
        boolean clearsLhOwnership = releasesLhEnrichment
                && (lhSourceSupplyRowIdentifier != null || lhTotalSupplyHouseholdCountOwned);
        if (hasSameSourceValues(incoming) && !clearsLhOwnership) {
            return false;
        }
        applySourceValues(incoming);
        if (clearsLhOwnership) {
            lhSourceSupplyRowIdentifier = null;
            lhTotalSupplyHouseholdCountOwned = false;
            lhTotalSupplyHouseholdCountEnriched = false;
        }
        return true;
    }

    public boolean updateDisplayOrder(int displayOrder) {
        if (adminModified) {
            return false;
        }
        validateNonNegative(displayOrder, "표시 순서");
        if (this.displayOrder == displayOrder) {
            return false;
        }
        this.displayOrder = displayOrder;
        return true;
    }

    public boolean resolveFromLhSupply(
            String sourceSupplyRowIdentifier,
            Integer resolvedTotalSupplyHouseholdCount,
            Integer lhSuppliedHouseholdCount
    ) {
        if (adminModified || announcement.isAdminDeleted()) {
            return false;
        }
        boolean sourceChanged = !Objects.equals(lhSourceSupplyRowIdentifier, sourceSupplyRowIdentifier);
        if (sourceChanged) {
            boolean changed = !Objects.equals(lhSourceSupplyRowIdentifier, sourceSupplyRowIdentifier)
                    || !Objects.equals(totalSupplyHouseholdCount, resolvedTotalSupplyHouseholdCount)
                    || lhTotalSupplyHouseholdCountOwned != (lhSuppliedHouseholdCount != null)
                    || lhTotalSupplyHouseholdCountEnriched;
            lhSourceSupplyRowIdentifier = sourceSupplyRowIdentifier;
            totalSupplyHouseholdCount = resolvedTotalSupplyHouseholdCount;
            lhTotalSupplyHouseholdCountOwned = lhSuppliedHouseholdCount != null;
            lhTotalSupplyHouseholdCountEnriched = false;
            return changed;
        }
        if (lhSuppliedHouseholdCount == null || lhTotalSupplyHouseholdCountEnriched) {
            return false;
        }
        if (Objects.equals(totalSupplyHouseholdCount, lhSuppliedHouseholdCount)
                && lhTotalSupplyHouseholdCountOwned) {
            return false;
        }
        totalSupplyHouseholdCount = lhSuppliedHouseholdCount;
        lhTotalSupplyHouseholdCountOwned = true;
        return true;
    }

    private boolean hasSameSourceValues(SupplyRow incoming) {
        return Objects.equals(housingComplex, incoming.housingComplex)
                && Objects.equals(housingType, incoming.housingType)
                && displayOrder == incoming.displayOrder
                && sourceComplexName.equals(incoming.sourceComplexName)
                && sourceHousingTypeName.equals(incoming.sourceHousingTypeName)
                && supplyPnu.equals(incoming.supplyPnu)
                && Objects.equals(expectedMoveInMonth, incoming.expectedMoveInMonth)
                && supplyCategory == incoming.supplyCategory
                && Objects.equals(matchingFailureReason, incoming.matchingFailureReason)
                && Objects.equals(totalSupplyHouseholdCount, incoming.totalSupplyHouseholdCount);
    }

    private void applySourceValues(SupplyRow incoming) {
        housingComplex = incoming.housingComplex;
        housingType = incoming.housingType;
        displayOrder = incoming.displayOrder;
        sourceComplexName = incoming.sourceComplexName;
        sourceHousingTypeName = incoming.sourceHousingTypeName;
        supplyPnu = incoming.supplyPnu;
        expectedMoveInMonth = incoming.expectedMoveInMonth;
        supplyCategory = incoming.supplyCategory;
        matchingFailureReason = incoming.matchingFailureReason;
        totalSupplyHouseholdCount = incoming.totalSupplyHouseholdCount;
    }

    public boolean enrichFromLh(
            String sourceSupplyRowIdentifier,
            YearMonth expectedMoveInMonth,
            Integer totalSupplyHouseholdCount
    ) {
        if (adminModified || announcement.isAdminDeleted()) {
            return false;
        }
        YearMonth ownedExpectedMoveInMonth = expectedMoveInMonth == null
                ? this.expectedMoveInMonth
                : expectedMoveInMonth;
        Integer ownedTotalSupplyHouseholdCount = totalSupplyHouseholdCount == null
                ? this.totalSupplyHouseholdCount
                : totalSupplyHouseholdCount;
        boolean ownsTotalSupplyHouseholdCount = totalSupplyHouseholdCount != null
                || lhTotalSupplyHouseholdCountOwned;
        boolean enrichesTotalSupplyHouseholdCount = totalSupplyHouseholdCount != null
                || lhTotalSupplyHouseholdCountEnriched;
        if (Objects.equals(lhSourceSupplyRowIdentifier, sourceSupplyRowIdentifier)
                && Objects.equals(this.expectedMoveInMonth, ownedExpectedMoveInMonth)
                && Objects.equals(this.totalSupplyHouseholdCount, ownedTotalSupplyHouseholdCount)
                && lhTotalSupplyHouseholdCountOwned == ownsTotalSupplyHouseholdCount
                && lhTotalSupplyHouseholdCountEnriched == enrichesTotalSupplyHouseholdCount) {
            return false;
        }
        lhSourceSupplyRowIdentifier = sourceSupplyRowIdentifier;
        this.expectedMoveInMonth = ownedExpectedMoveInMonth;
        this.totalSupplyHouseholdCount = ownedTotalSupplyHouseholdCount;
        lhTotalSupplyHouseholdCountOwned = ownsTotalSupplyHouseholdCount;
        lhTotalSupplyHouseholdCountEnriched = enrichesTotalSupplyHouseholdCount;
        return true;
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

    private void validateNotBlankIfPresent(String value, String fieldName) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 비어 있을 수 없다.");
        }
    }

    private void validateNonNegativeIfPresent(Integer value, String fieldName) {
        if (value != null) {
            validateNonNegative(value, fieldName);
        }
    }
}
