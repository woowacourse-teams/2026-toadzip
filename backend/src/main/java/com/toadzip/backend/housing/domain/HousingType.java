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
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "housing_types")
@NoArgsConstructor(access = PROTECTED)
public class HousingType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "housing_complex_id", nullable = false)
    private HousingComplex housingComplex;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal exclusiveArea;

    private BigDecimal supplyArea;

    @Column(nullable = false)
    private BigDecimal residentialCommonArea;

    @Column(nullable = false)
    private int totalHouseholdCount;

    @Column(nullable = false)
    private String floorPlanUrl;

    @Column(nullable = false)
    private boolean duplex;

    private BigDecimal maintenanceFee;

    private HousingType(
            HousingComplex housingComplex,
            String name,
            BigDecimal exclusiveArea,
            BigDecimal supplyArea,
            BigDecimal residentialCommonArea,
            int totalHouseholdCount,
            String floorPlanUrl,
            boolean duplex,
            BigDecimal maintenanceFee
    ) {
        validateRequired(housingComplex, "소속 단지");
        validateNotBlank(name, "주택형명");
        validateRequiredAmount(exclusiveArea, "전용면적");
        validateNonNegativeIfPresent(supplyArea, "공급면적");
        validateRequiredAmount(residentialCommonArea, "주거공용면적");
        validateNonNegative(totalHouseholdCount, "전체 세대수");
        validateNotBlank(floorPlanUrl, "평면도");
        validateNonNegativeIfPresent(maintenanceFee, "관리비");
        this.housingComplex = housingComplex;
        this.name = name;
        this.exclusiveArea = exclusiveArea;
        this.supplyArea = supplyArea;
        this.residentialCommonArea = residentialCommonArea;
        this.totalHouseholdCount = totalHouseholdCount;
        this.floorPlanUrl = floorPlanUrl;
        this.duplex = duplex;
        this.maintenanceFee = maintenanceFee;
    }

    public static HousingType create(
            HousingComplex housingComplex,
            String name,
            BigDecimal exclusiveArea,
            BigDecimal supplyArea,
            BigDecimal residentialCommonArea,
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
                residentialCommonArea,
                totalHouseholdCount,
                floorPlanUrl,
                duplex,
                maintenanceFee
        );
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
