package com.toadzip.backend.announcement.domain;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

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

    /** 관리자가 고친 표기값은 그대로 두고, 저장한 매칭이나 관리자 선택의 단지·주택형으로 연결만 바꾼다. */
    public boolean relinkByAdminMatch(HousingComplex complex, HousingType type) {
        if (announcement.isAdminDeleted()) {
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

    /**
     * 기관 원천(GH 공급행 틀)이 준 주택형 전용면적을 공고 면적 칸에 적어 둔다. PDF 추출이 틀 행과 PDF 행을 맞출 때 읽는다.
     * 면적 보정(areaOverride)은 켜지 않아 화면·공개 면적(effective*Area)은 그대로 연결 주택형을 따른다. 관리자 수정으로 보지 않는다.
     */
    public void recordSourceArea(BigDecimal exclusiveArea) {
        if (exclusiveArea != null && exclusiveArea.signum() < 0) {
            throw new IllegalArgumentException("면적은 음수일 수 없습니다.");
        }
        announcementExclusiveArea = exclusiveArea;
    }

    /** GH 공고 정제가 만든 공급행 틀이면 true다. 틀의 행 키는 `{공고 식별자}:{지구명}:{주택형 코드}`이고, 관리자가 추가한 행은 MANUAL: 키다. */
    public boolean ghFrameRow() {
        return announcement.origin() == AnnouncementOrigin.GH_SOURCE
                && sourceSupplyRowIdentifier.startsWith(announcement.getSourceAnnouncementIdentifier() + ":");
    }

    public void reorder(int order) {
        validateNonNegative(order, "표시 순서");
        displayOrder = order;
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

    /**
     * 이 공급행을 만든 첫 LH 공급 원천 행 식별자. LH 행이 붙는 공급행은 저장하지 않고 면적으로 계산하며(LhRowAttachments),
     * 이 값은 면적이 없는 LH 줄의 공급행과 단지 연결 이행이 읽는다.
     */
    private String lhSourceSupplyRowIdentifier;

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
            String matchingFailureReason
    ) {
        validateRequired(announcement, "공고");
        validateNotBlank(sourceSupplyRowIdentifier, "원천 공급행 식별자");
        validateNonNegative(displayOrder, "표시순서");
        validateNotBlank(sourceComplexName, "원천 단지명");
        validateNotBlank(sourceHousingTypeName, "원천 주택형명");
        validateNotBlankIfPresent(supplyPnu, "공급 PNU");
        validateRequired(supplyCategory, "공급구분");
        validateNotBlankIfPresent(matchingFailureReason, "매칭 실패 사유");
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
            String matchingFailureReason
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
                matchingFailureReason
        );
    }

    /**
     * LH 공급의 (공고 단지, 전용면적) 묶음으로 공급행을 만든다. 단지는 공고 단지의 연결 단지, 대표 주택형은 그 단지에서 LH 전용면적과
     * 같은 면적의 주택형이다. lhSourceSupplyRowIdentifier는 묶음의 첫 LH 줄이다. LH 원천 소유 공고에서만 쓴다.
     */
    public static SupplyRow createFromLh(Announcement announcement, AnnouncementComplex complex, HousingType type,
            String sourceSupplyRowIdentifier, String lhSourceSupplyRowIdentifier, int displayOrder, String typeName,
            BigDecimal lhExclusiveArea, YearMonth expectedMoveInMonth, SupplyCategory supplyCategory,
            LhSupplyMatchStatus status) {
        SupplyRow row = new SupplyRow(announcement, complex.linkedComplex(), type, sourceSupplyRowIdentifier,
                displayOrder, complex.getSourceComplexName(), typeName, null, expectedMoveInMonth, supplyCategory, null);
        row.announcementComplex = complex;
        row.lhExclusiveArea = lhExclusiveArea;
        row.lhMatchStatus = status;
        row.lhSourceSupplyRowIdentifier = lhSourceSupplyRowIdentifier;
        return row;
    }

    /**
     * 공고 공급표 쓰기(SupplySheetStore)가 처음 만드는 주택형. 원천 주택형명은 필수라 신청형 이름으로 채우고, 공급표가 정하지 않는 열은
     * 비워 둔다.
     */
    public static SupplyRow createFromSheet(Announcement announcement, String sourceSupplyRowIdentifier,
            SupplySheetDraft.HousingTypeRow values, AnnouncementComplex complex, HousingComplex housing,
            HousingType type) {
        SupplyRow row = new SupplyRow(announcement, housing, type, sourceSupplyRowIdentifier, values.displayOrder(),
                values.complexName(), values.typeName(), values.supplyPnu(), values.expectedMoveInMonth(),
                values.supplyCategory(), null);
        row.applySheet(values, complex, housing, type);
        return row;
    }

    /**
     * 공고 공급표가 정한 주택형 값으로 맞춘다. 신청형 이름은 공고문 이름이므로 공고 표시명에 쓴다. 원천 주택형명은 정제가 소유하는 매칭
     * 기준이라 바꾸지 않고, 공급표가 다루지 않는 열(관리자 제외·수정 표시, LH 대응)도 그대로 둔다.
     */
    public void applySheet(SupplySheetDraft.HousingTypeRow values, AnnouncementComplex complex, HousingComplex housing,
            HousingType type) {
        if (complex != null && !complex.getAnnouncement().getId().equals(announcement.getId())) {
            throw new IllegalArgumentException("다른 공고의 공고 단지는 연결할 수 없다.");
        }
        if (type != null && (housing == null || !Objects.equals(idOf(type.getHousingComplex()), idOf(housing)))) {
            throw new IllegalArgumentException("주택형은 연결한 단지의 주택형이어야 한다.");
        }
        announcementComplex = complex;
        housingComplex = housing;
        housingType = type;
        sourceComplexName = values.complexName();
        // 원천이 주인인 공고에서 신청형 이름이 원천 주택형명 그대로면 공고 표시명을 따로 두지 않는다. 공개 화면은 전처럼 연결한 단지
        // 주택형명을 보인다(LH 원천명 '59A(청년) · 59A(고령자)' 대신 '59A'). 공고문이 주인이면 신청형 이름이 공고 표시명이다.
        boolean sourceName = announcement.suppliedByLh() && values.typeName().equals(sourceHousingTypeName.strip());
        announcementHousingTypeName = sourceName ? null : values.typeName();
        // 면적 보정이 전후 모두 없으면 원천이 적어 둔 공고 면적 칸(recordSourceArea)을 지우지 않는다.
        if (areaOverride || values.overridesArea()) {
            areaOverride = values.overridesArea();
            announcementExclusiveArea = values.exclusiveArea();
            announcementSupplyArea = values.supplyArea();
        }
        expectedMoveInMonth = values.expectedMoveInMonth();
        supplyPnu = values.supplyPnu();
        supplyCategory = values.supplyCategory();
        displayOrder = values.displayOrder();
    }

    /**
     * 정제·등록이 쓴 이 공급행을 지금 값 그대로 공급표 주택형으로 옮긴다({@link SourceSupplySheet}). 신청형 이름은 원천 주택형명이다.
     * 원천 형명이 바뀌면 신청형 이름도 따라가도록 앞서 적힌 공고 표시명을 쓰지 않는다.
     * 관리자가 고친 행만 공급표 보기와 같은 이름(공고 표시명, 없으면 원천 주택형명)을 지킨다.
     */
    public SupplySheetDraft.HousingTypeRow sourceSheetRow(String key) {
        String typeName = (adminModified ? Objects.requireNonNullElse(announcementHousingTypeName, sourceHousingTypeName)
                : sourceHousingTypeName).strip();
        return new SupplySheetDraft.HousingTypeRow(key, id, idOf(announcementComplex), idOf(housingComplex),
                idOf(housingType), sourceComplexName, typeName, areaOverride ? announcementExclusiveArea : null,
                areaOverride ? announcementSupplyArea : null, expectedMoveInMonth, supplyPnu, supplyCategory,
                displayOrder);
    }

    /**
     * 공급표의 같은 주택형이면 true다. 신청형 이름(공고 표시명, 없으면 원천 주택형명)과, 공고 단지가 있으면 공고 단지, 없으면 단지명으로
     * 가린다.
     */
    public boolean sameSheetHousingType(SupplySheetDraft.HousingTypeRow values) {
        String applicationTypeName = Objects.requireNonNullElse(announcementHousingTypeName, sourceHousingTypeName);
        if (!applicationTypeName.strip().equals(values.typeName())) {
            return false;
        }
        if (announcementComplex != null || values.announcementComplexId() != null) {
            return Objects.equals(idOf(announcementComplex), values.announcementComplexId());
        }
        return sourceComplexName.strip().equals(values.complexName());
    }

    /**
     * LH 원천 소유 공고의 공급행을 이번 LH 묶음으로 맞춘다. 관리자가 고친 행은 값을 두고 첫 LH 줄 식별자만 옮긴다.
     * 휴지통 공고는 바꾸지 않는다. 바뀌었으면 true다.
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
                && Objects.equals(lhSourceSupplyRowIdentifier, incoming.lhSourceSupplyRowIdentifier)) {
            return false;
        }
        applySourceValues(incoming);
        announcementComplex = incoming.announcementComplex;
        lhExclusiveArea = incoming.lhExclusiveArea;
        lhMatchStatus = incoming.lhMatchStatus;
        lhSourceSupplyRowIdentifier = incoming.lhSourceSupplyRowIdentifier;
        return true;
    }

    /**
     * 비어 있는 공고 단지 참조만 채운다. 정제는 공고문 소유 공급행의 값·단지·주택형을 바꾸지 않고 이것만 채워 PDF가 정한 연결을 덮지
     * 않는다. 관리자 등록·JSON 등록도 새 행을 (공고, 단지명) 공고 단지에 이것으로 넣는다.
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

    private boolean hasSameSourceValues(SupplyRow incoming) {
        return Objects.equals(housingComplex, incoming.housingComplex)
                && Objects.equals(housingType, incoming.housingType)
                && displayOrder == incoming.displayOrder
                && sourceComplexName.equals(incoming.sourceComplexName)
                && sourceHousingTypeName.equals(incoming.sourceHousingTypeName)
                && Objects.equals(supplyPnu, incoming.supplyPnu)
                && Objects.equals(expectedMoveInMonth, incoming.expectedMoveInMonth)
                && supplyCategory == incoming.supplyCategory
                && Objects.equals(matchingFailureReason, incoming.matchingFailureReason);
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
}
