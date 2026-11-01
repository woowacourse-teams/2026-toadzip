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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.YearMonth;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
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

    /** 원천 단지명·주택형명은 정제가 소유하는 매칭 기준이므로 바꾸지 않고, 표기는 공고 표시명으로만 받는다. */
    public void reviseByAdmin(HousingComplex complex, HousingType type, String displayTypeName,
            String pnu, YearMonth moveInMonth, SupplyCategory category, Integer count) {
        if (supplyGroup != null && (count != null || complex == null || housingComplex == null
                || !complex.getId().equals(housingComplex.getId()))) {
            throw new IllegalArgumentException("공통 모집수와 단지는 원천별 공급행 보정에서 함께 변경해 주세요.");
        }
        SupplyRow incoming = create(announcement, complex, type, sourceSupplyRowIdentifier, displayOrder,
                sourceComplexName, sourceHousingTypeName, pnu, moveInMonth, category, null, count);
        // 표기를 따로 두지 않던 행이 화면에 보이던 이름을 그대로 보내면 표기를 바꾼 것이 아니다.
        // 연결이 함께 바뀌어도 새 주택형 이름을 따라가도록 이전 이름을 공고 표기로 굳히지 않는다.
        boolean keepsDefault = announcementHousingTypeName == null && displayTypeName != null
                && displayTypeName.strip().equals(defaultHousingTypeName());
        housingComplex = incoming.housingComplex;
        represent(incoming.housingType);
        announcementHousingTypeName = keepsDefault ? null : labelDifferentFromDefault(displayTypeName);
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
        represent(type);
        matchingFailureReason = null;
        return true;
    }

    public Set<HousingType> getHousingTypes() {
        return Set.copyOf(housingTypes);
    }

    /**
     * 대표 주택형을 바꾸면 함께 모집하는 주택형도 대표 하나로 맞춘다. 대표가 그대로면 함께 모집하는 주택형을 지우지 않는다.
     * PDF·공급 보정이 LH 한 행에 묶인 주택형 여럿 중 대표만 다시 저장해도 나머지 연결이 남게 하려는 것이다.
     */
    private void represent(HousingType type) {
        boolean sameRepresentative = type != null && housingType != null && sameType(housingType, type)
                && housingTypes.stream().anyMatch(linked -> sameType(linked, type));
        housingType = type;
        boolean unchanged = type == null ? housingTypes.isEmpty() : sameRepresentative
                || housingTypes.size() == 1 && sameType(housingTypes.iterator().next(), type);
        if (unchanged) {
            return;
        }
        housingTypes.clear();
        if (type != null) {
            housingTypes.add(type);
        }
    }

    private static boolean sameType(HousingType linked, HousingType type) {
        return linked == type || linked.getId() != null && linked.getId().equals(type.getId());
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

    /** 등록 단지에 없는 원천 공급행을 공개에서 빼거나 되돌린다. 정제는 제외된 행의 매칭 실패를 오류로 남기지 않는다. */
    public boolean excludeByAdmin(boolean excluded) {
        if (adminExcluded == excluded) {
            return false;
        }
        adminExcluded = excluded;
        return true;
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

    /** 공개 화면의 주택형 이름은 공고 표시명 → 단지 주택형명 → 원천 주택형명 순서로 정한다. */
    public String displayHousingTypeName() {
        if (announcementHousingTypeName != null) { return announcementHousingTypeName; }
        return defaultHousingTypeName();
    }

    private String defaultHousingTypeName() {
        if (housingType != null) { return housingType.getName(); }
        return sourceHousingTypeName;
    }

    // 기본 표시와 같은 값을 따로 저장하면 단지 주택형 이름을 고쳐도 이 공고에 반영되지 않는다.
    private String labelDifferentFromDefault(String label) {
        if (label == null || label.isBlank()) { return null; }
        String stripped = label.strip();
        if (stripped.length() > 255) {
            throw new IllegalArgumentException("공고 표시명은 255자 이하로 입력해 주세요.");
        }
        return stripped.equals(defaultHousingTypeName()) ? null : stripped;
    }

    private String announcementHousingTypeName;

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

    /** 함께 모집하는 주택형. 대표 주택형도 포함한다. 지금은 대표 주택형 하나만 연결한다. */
    @Getter(AccessLevel.NONE)
    @ManyToMany(fetch = LAZY)
    @JoinTable(name = "supply_row_housing_types",
            joinColumns = @JoinColumn(name = "supply_row_id"),
            inverseJoinColumns = @JoinColumn(name = "housing_type_id"))
    private Set<HousingType> housingTypes = new HashSet<>();

    @Column(nullable = false)
    private String sourceSupplyRowIdentifier;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private String sourceComplexName;

    @Column(nullable = false)
    private String sourceHousingTypeName;

    /** LH 원천으로 만든 공급행은 공급 PNU가 없을 수 있다. */
    private String supplyPnu;

    /** 이 공급행이 속한 공고 단지. LH 원천으로 만든 공급행만 채워지고, 공고 단지를 모르는 행은 비어 있다. */
    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "announcement_complex_id")
    private AnnouncementComplex announcementComplex;

    /** LH가 준 대표 전용면적. 함께 모집하는 면적 중 하나이고 어느 면적을 고르는지는 일정하지 않다. */
    @Column(precision = 10, scale = 4)
    private BigDecimal lhExclusiveArea;

    @Enumerated(STRING)
    @Column(length = 30)
    private LhSupplyMatchStatus lhMatchStatus;

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

    /** 원천 분할 단지를 합쳐 같은 주택형의 소속 단지만 바뀐 경우 확인한 대상 지문을 다시 계산한다. */
    public void reconfirmTargetAfterComplexMove(String targetFingerprint) {
        confirmedTargetFingerprint = targetFingerprint;
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
        validateNotBlankIfPresent(supplyPnu, "공급 PNU");
        validateRequired(supplyCategory, "공급구분");
        validateNotBlankIfPresent(matchingFailureReason, "매칭 실패 사유");
        validateNonNegativeIfPresent(totalSupplyHouseholdCount, "전체 공급세대수");
        this.announcement = announcement;
        this.housingComplex = housingComplex;
        represent(housingType);
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

    /**
     * LH 공급 한 행으로 공급행을 만든다. 단지는 공고 단지의 연결 단지, 대표 주택형은 그 단지에서 LH 대표 전용면적과 면적이 같은 주택형이다.
     * LH 원천 소유 공고에서만 쓴다.
     */
    public static SupplyRow createFromLh(Announcement announcement, AnnouncementComplex complex, HousingType type,
            String sourceSupplyRowIdentifier, String lhSourceSupplyRowIdentifier, int displayOrder, String typeName,
            BigDecimal lhExclusiveArea, YearMonth expectedMoveInMonth, SupplyCategory supplyCategory,
            Integer supplyHouseholdCount, LhSupplyMatchStatus status) {
        SupplyRow row = new SupplyRow(announcement, complex.linkedComplex(), type, sourceSupplyRowIdentifier,
                displayOrder, complex.getSourceComplexName(), typeName, null, expectedMoveInMonth, supplyCategory, null,
                supplyHouseholdCount);
        row.announcementComplex = complex;
        row.lhExclusiveArea = lhExclusiveArea;
        row.lhMatchStatus = status;
        row.lhSourceSupplyRowIdentifier = lhSourceSupplyRowIdentifier;
        row.lhTotalSupplyHouseholdCountOwned = supplyHouseholdCount != null;
        row.lhTotalSupplyHouseholdCountEnriched = supplyHouseholdCount != null;
        return row;
    }

    /**
     * LH 원천 소유 공고의 공급행을 이번 LH 행으로 맞춘다. 관리자가 고친 행은 값을 두고 LH 원천 행과의 대응만 기록한다.
     * 원천 순번이 바뀌어도 작업실이 이 행을 그 LH 행에 붙은 공급행으로 보게 하려는 것이다. 휴지통 공고는 바꾸지 않는다. 바뀌었으면 true다.
     */
    public boolean updateFromLh(SupplyRow incoming) {
        if (announcement.isAdminDeleted()) {
            return false;
        }
        if (adminModified) {
            if (Objects.equals(lhSourceSupplyRowIdentifier, incoming.lhSourceSupplyRowIdentifier)) {
                return false;
            }
            lhSourceSupplyRowIdentifier = incoming.lhSourceSupplyRowIdentifier;
            return true;
        }
        if (hasSameSourceValues(incoming) && Objects.equals(idOf(announcementComplex), idOf(incoming.announcementComplex))
                && Objects.equals(lhExclusiveArea, incoming.lhExclusiveArea) && lhMatchStatus == incoming.lhMatchStatus
                && Objects.equals(lhSourceSupplyRowIdentifier, incoming.lhSourceSupplyRowIdentifier)
                && lhTotalSupplyHouseholdCountOwned == incoming.lhTotalSupplyHouseholdCountOwned) {
            return false;
        }
        applySourceValues(incoming);
        announcementComplex = incoming.announcementComplex;
        lhExclusiveArea = incoming.lhExclusiveArea;
        lhMatchStatus = incoming.lhMatchStatus;
        lhSourceSupplyRowIdentifier = incoming.lhSourceSupplyRowIdentifier;
        lhTotalSupplyHouseholdCountOwned = incoming.lhTotalSupplyHouseholdCountOwned;
        lhTotalSupplyHouseholdCountEnriched = incoming.lhTotalSupplyHouseholdCountEnriched;
        return true;
    }

    /**
     * 공고문 소유 공급행은 정제가 값·단지·주택형을 바꾸지 않고 비어 있는 공고 단지 참조만 채운다. PDF가 정한 연결을 덮지 않기 위해서다.
     */
    public boolean fillAnnouncementComplex(AnnouncementComplex complex) {
        if (announcementComplex != null || complex == null) {
            return false;
        }
        announcementComplex = complex;
        return true;
    }

    private static Long idOf(AnnouncementComplex complex) {
        return complex == null ? null : complex.getId();
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
            // 원천 이름 갱신은 공고 값 변경이 아니므로 변경으로 세지 않는다. 세면 정제 중 LH 재보강이 실행되어
            // 다른 행의 보강 실패가 이 공고의 정제 전체를 되돌린다.
            refreshSourceNames(sourceComplexName, sourceHousingTypeName);
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

    // 관리자 보정 행도 원천 이름은 원천 값을 따라야 다음 매칭이 관리자 표기 때문에 끊기지 않는다.
    private void refreshSourceNames(String complexName, String typeName) {
        validateNotBlank(complexName, "원천 단지명");
        validateNotBlank(typeName, "원천 주택형명");
        sourceComplexName = complexName;
        sourceHousingTypeName = typeName;
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
        if (announcement.isAdminDeleted()) {
            return false;
        }
        if (adminModified) {
            // 관리자 보정 행도 LH 원본과의 연결은 정제가 기록해야 보강이 단지명을 다시 비교하지 않는다.
            // 관리자가 정한 모집수는 유지하고, 연결 기록만으로는 정제 중 LH 재보강을 일으키지 않는다.
            lhSourceSupplyRowIdentifier = sourceSupplyRowIdentifier;
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

    /**
     * 관리자가 LH 공급 연결을 바꿔 기록한 LH 원천 행이 이제 다른 마이홈 원천행({@code resolvedSourceRowIdentifier})으로
     * 해석되거나 어느 원천행으로도 해석되지 않으면({@code null}) 그 LH 연결을 놓는다. 보강은 기록한 LH 식별자를 저장 연결보다 먼저 쓰므로 놓지 않으면 재정제 전 보강이
     * 이전 행에 금액을 붙인다. 관리자 보정 행도 LH 연결은 정제가 기록하므로 함께 놓는다. 값은 바꾸지 않는다.
     * 관리자가 정하지 않은 모집수는 다음 정제가 마이홈 값으로 되돌리고, LH가 채운 입주월은 그대로 남는다.
     */
    public boolean releaseLhSourceResolvedElsewhere(String resolvedSourceRowIdentifier) {
        if (lhSourceSupplyRowIdentifier == null
                || Objects.equals(myhomeSourceRowIdentifier, resolvedSourceRowIdentifier)) {
            return false;
        }
        lhSourceSupplyRowIdentifier = null;
        lhTotalSupplyHouseholdCountOwned = false;
        lhTotalSupplyHouseholdCountEnriched = false;
        return true;
    }

    private boolean hasSameSourceValues(SupplyRow incoming) {
        return Objects.equals(housingComplex, incoming.housingComplex)
                && Objects.equals(housingType, incoming.housingType)
                && displayOrder == incoming.displayOrder
                && sourceComplexName.equals(incoming.sourceComplexName)
                && sourceHousingTypeName.equals(incoming.sourceHousingTypeName)
                && Objects.equals(supplyPnu, incoming.supplyPnu)
                && Objects.equals(expectedMoveInMonth, incoming.expectedMoveInMonth)
                && supplyCategory == incoming.supplyCategory
                && Objects.equals(matchingFailureReason, incoming.matchingFailureReason)
                && Objects.equals(totalSupplyHouseholdCount, incoming.totalSupplyHouseholdCount);
    }

    private void applySourceValues(SupplyRow incoming) {
        housingComplex = incoming.housingComplex;
        represent(incoming.housingType);
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
