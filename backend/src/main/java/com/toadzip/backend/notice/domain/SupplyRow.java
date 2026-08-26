package com.toadzip.backend.notice.domain;

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
import java.time.YearMonth;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "supply_rows")
@NoArgsConstructor(access = PROTECTED)
public class SupplyRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false)
    private Notice notice;

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
    @Column(nullable = false)
    private YearMonth expectedMoveInMonth;

    @Enumerated(STRING)
    @Column(nullable = false)
    private SupplyCategory supplyCategory;

    private String matchingFailureReason;

    @Column(nullable = false)
    private int totalSupplyHouseholdCount;

    private SupplyRow(
            Notice notice,
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
            int totalSupplyHouseholdCount
    ) {
        validateRequired(notice, "공고");
        validateNotBlank(sourceSupplyRowIdentifier, "원천 공급행 식별자");
        validateNonNegative(displayOrder, "표시순서");
        validateNotBlank(sourceComplexName, "원천 단지명");
        validateNotBlank(sourceHousingTypeName, "원천 주택형명");
        validateNotBlank(supplyPnu, "공급 PNU");
        validateRequired(expectedMoveInMonth, "입주 예정 연월");
        validateRequired(supplyCategory, "공급구분");
        validateNonNegative(totalSupplyHouseholdCount, "전체 공급세대수");
        this.notice = notice;
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
            Notice notice,
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
            int totalSupplyHouseholdCount
    ) {
        return new SupplyRow(
                notice,
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
}
