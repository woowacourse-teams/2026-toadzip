package com.toadzip.backend.housing.domain;

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
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "housing_types",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_housing_type_source_identifier",
                columnNames = "source_housing_type_identifier"
        )
)
@NoArgsConstructor(access = PROTECTED)
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

    private Integer totalHouseholdCount;

    private String floorPlanUrl;

    private Boolean duplex;

    private BigDecimal maintenanceFee;

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
        this.exclusiveArea = exclusiveArea;
        this.supplyArea = supplyArea;
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

    public boolean updateFromMyHome(String name, BigDecimal exclusiveArea, BigDecimal supplyArea) {
        validateNotBlank(name, "주택형명");
        validateRequiredAmount(exclusiveArea, "전용면적");
        validateNonNegativeIfPresent(supplyArea, "공급면적");
        if (this.name.equals(name)
                && this.exclusiveArea.compareTo(exclusiveArea) == 0
                && sameAmount(this.supplyArea, supplyArea)) {
            return false;
        }
        this.name = name;
        this.exclusiveArea = exclusiveArea;
        this.supplyArea = supplyArea;
        return true;
    }

    public boolean isDuplex() {
        return Boolean.TRUE.equals(duplex);
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
