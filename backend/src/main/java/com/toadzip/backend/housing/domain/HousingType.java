package com.toadzip.backend.housing.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Embedded;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

@Getter
@Entity
@Table(
        name = "housing_types",
        indexes = @Index(name = "idx_housing_types_housing_complex_id", columnList = "housing_complex_id"),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_housing_type_source_identifier",
                columnNames = "source_housing_type_identifier"
        )
)
@NoArgsConstructor(access = PROTECTED)
// 휴지통 주택형은 기본 조회에서 빠진다. 원천 동기화와 관리자 상세만 HousingTypeRepository의 포함 조회를 쓴다.
@SQLRestriction("admin_deleted = false")
public class HousingType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "housing_complex_id", nullable = false)
    private HousingComplex housingComplex;

    @Column(length = 500)
    private String sourceHousingTypeIdentifier;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal exclusiveArea;

    @Column(precision = 10, scale = 4)
    private BigDecimal supplyArea;

    @Column(nullable = true)
    private Integer totalHouseholdCount;

    @Column(nullable = true)
    private String floorPlanUrl;

    @Column(nullable = true)
    private Boolean duplex;

    @Column(nullable = true)
    private BigDecimal maintenanceFee;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean adminModified;

    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean adminDeleted;

    /** 관리자가 직접 바꾼 이름. 공고가 덮지 않는다. */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean nameAdminModified;

    /** 관리자가 직접 바꾼 세대수. 공고가 덮지 않는다. */
    @Column(nullable = false)
    @org.hibernate.annotations.ColumnDefault("false")
    private boolean householdCountAdminModified;

    /** 마지막으로 값을 반영한 공고의 게시 시각. 이보다 이른 공고는 값을 되돌리지 않는다. */
    private Instant announcementObservedAt;

    @Embedded
    private MyHomeRentalCondition basicRentalCondition;

    /** 기본 임대조건을 채운 곳. 마이홈 단지 정제면 MYHOME, PDF 보정이 공고 값으로 바꿨으면 ANNOUNCEMENT다. */
    @Column(length = 20)
    private String rentalConditionSource;

    private HousingType(
            HousingComplex housingComplex,
            String name,
            BigDecimal exclusiveArea,
            BigDecimal supplyArea,
            int totalHouseholdCount,
            String floorPlanUrl,
            boolean duplex,
            BigDecimal maintenanceFee
    ) {
        this(housingComplex, null, name, exclusiveArea, supplyArea);
        validateNonNegative(totalHouseholdCount, "전체 세대수");
        validateNotBlank(floorPlanUrl, "평면도");
        validateNonNegativeIfPresent(maintenanceFee, "관리비");
        this.totalHouseholdCount = totalHouseholdCount;
        this.floorPlanUrl = floorPlanUrl;
        this.duplex = duplex;
        this.maintenanceFee = maintenanceFee;
    }

    private HousingType(
            HousingComplex housingComplex,
            String sourceHousingTypeIdentifier,
            String name,
            BigDecimal exclusiveArea,
            BigDecimal supplyArea
    ) {
        validateRequired(housingComplex, "소속 단지");
        validateNotBlank(name, "주택형명");
        validateRequiredAmount(exclusiveArea, "전용면적");
        validateNonNegativeIfPresent(supplyArea, "공급면적");
        this.housingComplex = housingComplex;
        this.sourceHousingTypeIdentifier = sourceHousingTypeIdentifier;
        this.name = name;
        this.exclusiveArea = area(exclusiveArea);
        this.supplyArea = area(supplyArea);
    }

    public static HousingType create(
            HousingComplex housingComplex,
            String name,
            BigDecimal exclusiveArea,
            BigDecimal supplyArea,
            int totalHouseholdCount,
            String floorPlanUrl,
            boolean duplex,
            BigDecimal maintenanceFee
    ) {
        return new HousingType(
                housingComplex,
                name,
                exclusiveArea,
                supplyArea,
                totalHouseholdCount,
                floorPlanUrl,
                duplex,
                maintenanceFee
        );
    }

    public static HousingType createFromMyHome(
            HousingComplex housingComplex,
            String sourceHousingTypeIdentifier,
            String name,
            BigDecimal exclusiveArea,
            BigDecimal supplyArea
    ) {
        validateNotBlankStatic(sourceHousingTypeIdentifier, "원천 주택형 식별자");
        return new HousingType(
                housingComplex,
                sourceHousingTypeIdentifier,
                name,
                exclusiveArea,
                supplyArea
        );
    }

    /**
     * 관리자가 단지 상세에서 직접 만든 주택형. 수집 원천이 없으므로 원천 식별자를 새로 만들고, 입력한 이름과 세대수는
     * 관리자 수정으로 보호해 공고가 덮지 않게 한다.
     */
    public static HousingType createByAdmin(
            HousingComplex housingComplex,
            String sourceHousingTypeIdentifier,
            String name,
            BigDecimal exclusiveArea,
            BigDecimal supplyArea,
            Integer householdCount
    ) {
        var type = createFromMyHome(housingComplex, sourceHousingTypeIdentifier, name, exclusiveArea, supplyArea);
        type.reviseByAdmin(name, exclusiveArea, householdCount);
        type.nameAdminModified = true;
        type.householdCountAdminModified = householdCount != null;
        return type;
    }

    /**
     * 관리자가 PDF 보정에서 공고문 값으로 만든 주택형. 공고 값이라 보호하지 않아 다음 공고가 이름·공급면적·세대수를 갱신한다.
     */
    public static HousingType createFromAnnouncement(
            HousingComplex housingComplex,
            String sourceHousingTypeIdentifier,
            String name,
            BigDecimal exclusiveArea,
            BigDecimal supplyArea,
            Integer householdCount
    ) {
        var type = createFromMyHome(housingComplex, sourceHousingTypeIdentifier, name, exclusiveArea, supplyArea);
        if (householdCount != null) {
            type.validateNonNegative(householdCount, "전체 세대수");
        }
        type.totalHouseholdCount = householdCount;
        return type;
    }

    /** 관리자가 단지 상세나 PDF 보정에서 직접 만든 주택형의 원천 식별자 접두어 */
    public static final String ADMIN_SOURCE_PREFIX = "ADMIN_ENTRY-HOUSING-TYPE-";

    public boolean isAdminCreated() {
        return sourceHousingTypeIdentifier.startsWith(ADMIN_SOURCE_PREFIX);
    }

    /**
     * 관리자가 만든 주택형을 같은 마이홈 주택형으로 이어받는다. 이후 정제가 원천 식별자로 이 주택형을 찾고,
     * 이름·면적은 관리자가 정한 값을 유지하며 비어 있는 공급면적만 채운다.
     */
    public void adoptMyHomeSource(String sourceHousingTypeIdentifier, BigDecimal supplyArea) {
        validateNotBlankStatic(sourceHousingTypeIdentifier, "원천 주택형 식별자");
        validateNonNegativeIfPresent(supplyArea, "공급면적");
        this.sourceHousingTypeIdentifier = sourceHousingTypeIdentifier;
        if (this.supplyArea == null && supplyArea != null) {
            this.supplyArea = area(supplyArea);
        }
    }

    public boolean updateFromMyHome(
            String sourceHousingTypeIdentifier,
            String name,
            BigDecimal exclusiveArea,
            BigDecimal supplyArea
    ) {
        validateNotBlankStatic(sourceHousingTypeIdentifier, "원천 주택형 식별자");
        validateNotBlank(name, "주택형명");
        validateRequiredAmount(exclusiveArea, "전용면적");
        validateNonNegativeIfPresent(supplyArea, "공급면적");
        if (adminModified) {
            return false;
        }
        if (this.sourceHousingTypeIdentifier.equals(sourceHousingTypeIdentifier)
                && this.name.equals(name)
                && this.exclusiveArea.compareTo(exclusiveArea) == 0
                && sameAmount(this.supplyArea, supplyArea)) {
            return false;
        }
        this.sourceHousingTypeIdentifier = sourceHousingTypeIdentifier;
        this.name = name;
        this.exclusiveArea = area(exclusiveArea);
        this.supplyArea = area(supplyArea);
        return true;
    }

    public boolean enrichHouseholdCountFromLh(int totalHouseholdCount) {
        validateNonNegative(totalHouseholdCount, "LH 주택형 세대수");
        if (adminModified) {
            return false;
        }
        if (Objects.equals(this.totalHouseholdCount, totalHouseholdCount)) {
            return false;
        }
        this.totalHouseholdCount = totalHouseholdCount;
        return true;
    }

    /**
     * 면적은 DB 열과 같은 소수 넷째 자리로 맞춘다. 새로 만든 주택형의 메모리 값(16.37)과 다시 읽은 값(16.3700)이 달라
     * 같은 주택형인데도 저장 매칭의 대상 지문이 달라지는 일을 막는다.
     */
    private static BigDecimal area(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    /** 관리자 판단을 원천 재수집이 되돌리지 않도록 관리자 수정으로 함께 표시한다. */
    public void moveToTrash() {
        adminDeleted = true;
        adminModified = true;
    }

    public void restore() {
        adminDeleted = false;
    }

    public void reviseByAdmin(String name, BigDecimal exclusiveArea, Integer householdCount) {
        validateNotBlank(name, "주택형명");
        validateRequiredAmount(exclusiveArea, "전용면적");
        if (householdCount != null) {
            validateNonNegative(householdCount, "전체 세대수");
        }
        // 공고가 쓰는 필드는 관리자가 실제로 바꾼 것만 보호한다. 다른 필드를 고쳤다고 공고 갱신이 멈추지 않게 한다.
        if (!this.name.equals(name)) { nameAdminModified = true; }
        if (!Objects.equals(this.totalHouseholdCount, householdCount)) { householdCountAdminModified = true; }
        this.name = name;
        this.exclusiveArea = area(exclusiveArea);
        this.totalHouseholdCount = householdCount;
        this.adminModified = true;
    }

    /** 공고가 주택형에 쓰는 값. 공고에 없는 값은 null이다. 기본 보증금·월 임대료는 PDF 보정이 고른 공급대상의 금액이다. */
    public record AnnouncementValues(String name, BigDecimal supplyArea, Integer householdCount, Long basicDeposit,
            Long basicMonthlyRent) {
        public AnnouncementValues(String name, BigDecimal supplyArea, Integer householdCount) {
            this(name, supplyArea, householdCount, null, null);
        }
    }

    public AnnouncementValues announcementValues() {
        return new AnnouncementValues(name, supplyArea, totalHouseholdCount,
                basicRentalCondition == null ? null : basicRentalCondition.deposit(),
                basicRentalCondition == null ? null : basicRentalCondition.monthlyRent());
    }

    /**
     * 게시 시각 postedAt인 공고 값을 반영한 뒤의 값. 공고에 없는 값과 관리자가 직접 바꾼 이름·세대수는 지금 값을 둔다.
     * 공급면적과 기본 임대조건은 관리자가 고치는 값이 아니라 보호하지 않는다(PDF 보정 검토 화면에서 관리자가 고른다).
     * 마지막으로 반영한 공고보다 이른 공고면 지금 값 그대로다.
     */
    public AnnouncementValues afterAnnouncement(AnnouncementValues incoming, Instant postedAt) {
        var current = announcementValues();
        if (announcementObservedAt != null && postedAt.isBefore(announcementObservedAt)) {
            return current;
        }
        String nextName = current.name();
        if (!nameAdminModified && incoming.name() != null && !incoming.name().isBlank()) { nextName = incoming.name().strip(); }
        BigDecimal nextSupplyArea = current.supplyArea();
        if (incoming.supplyArea() != null) { nextSupplyArea = area(incoming.supplyArea()); }
        Integer nextCount = current.householdCount();
        if (!householdCountAdminModified && incoming.householdCount() != null) { nextCount = incoming.householdCount(); }
        Long nextDeposit = incoming.basicDeposit() != null ? incoming.basicDeposit() : current.basicDeposit();
        Long nextRent = incoming.basicMonthlyRent() != null ? incoming.basicMonthlyRent() : current.basicMonthlyRent();
        return new AnnouncementValues(nextName, nextSupplyArea, nextCount, nextDeposit, nextRent);
    }

    /** 공고 값을 반영하고 반영한 공고 게시 시각을 남긴다. 값이 바뀌었으면 true다. */
    public boolean observeAnnouncement(AnnouncementValues incoming, Instant postedAt) {
        validateNonNegativeIfPresent(incoming.supplyArea(), "공급면적");
        if (incoming.householdCount() != null) { validateNonNegative(incoming.householdCount(), "전체 세대수"); }
        if (incoming.basicDeposit() != null && incoming.basicDeposit() < 0
                || incoming.basicMonthlyRent() != null && incoming.basicMonthlyRent() < 0) {
            throw new IllegalArgumentException("기본 임대금액은 음수일 수 없습니다.");
        }
        var current = announcementValues();
        var next = afterAnnouncement(incoming, postedAt);
        if (announcementObservedAt == null || postedAt.isAfter(announcementObservedAt)) { announcementObservedAt = postedAt; }
        if (next.equals(current)) { return false; }
        name = next.name();
        supplyArea = next.supplyArea();
        totalHouseholdCount = next.householdCount();
        if (!Objects.equals(next.basicDeposit(), current.basicDeposit())
                || !Objects.equals(next.basicMonthlyRent(), current.basicMonthlyRent())) {
            // 공고 값으로 바꾼 기본 임대조건의 날짜는 그 공고의 게시 시각이다.
            basicRentalCondition = new MyHomeRentalCondition(next.basicDeposit(), next.basicMonthlyRent(), postedAt);
            rentalConditionSource = "ANNOUNCEMENT";
        }
        return true;
    }

    /**
     * 마이홈 단지 정제가 기본 임대조건을 채운다. PDF 보정이 공고 값으로 바꾼 조건은 덮지 않는다.
     * 최초 수집 뒤 주택형 정보는 공고로만 바꾸기로 했고, 덮으면 정제와 PDF 보정이 값을 번갈아 뒤집는다.
     */
    public boolean updateBasicRentalCondition(MyHomeRentalCondition condition) {
        if ("ANNOUNCEMENT".equals(rentalConditionSource)) {
            return false;
        }
        if (Objects.equals(basicRentalCondition, condition)) {
            return false;
        }
        basicRentalCondition = condition;
        rentalConditionSource = condition == null ? null : "MYHOME";
        return true;
    }

    public boolean isDuplex() {
        return Boolean.TRUE.equals(duplex);
    }

    public boolean hasSupplementalInformation() {
        return totalHouseholdCount != null || floorPlanUrl != null || duplex != null || maintenanceFee != null;
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    private static void validateNotBlankStatic(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
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

    private void validateRequiredAmount(BigDecimal value, String fieldName) {
        validateRequired(value, fieldName);
        validateNonNegativeIfPresent(value, fieldName);
    }

    private void validateNonNegativeIfPresent(BigDecimal value, String fieldName) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }

    private void validateNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }
}
