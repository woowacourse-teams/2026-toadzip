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

    @Embedded
    private MyHomeRentalCondition basicRentalCondition;

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
        if (java.util.Objects.equals(this.totalHouseholdCount, totalHouseholdCount)) {
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
        this.name = name;
        this.exclusiveArea = area(exclusiveArea);
        this.totalHouseholdCount = householdCount;
        this.adminModified = true;
    }

    public boolean updateBasicRentalCondition(MyHomeRentalCondition condition) {
        if (Objects.equals(basicRentalCondition, condition)) {
            return false;
        }
        basicRentalCondition = condition;
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
