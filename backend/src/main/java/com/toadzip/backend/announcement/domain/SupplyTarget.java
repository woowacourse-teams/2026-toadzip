package com.toadzip.backend.announcement.domain;

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
@Table(name = "supply_targets")
@NoArgsConstructor(access = PROTECTED)
public class SupplyTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "supply_row_id", nullable = false)
    private SupplyRow supplyRow;

    @Column(nullable = false)
    private String target;

    private String supplyRank;

    private Integer supplyHouseholdCount;

    private Integer reserveCount;

    private BigDecimal rentalDeposit;

    private BigDecimal monthlyRent;

    private BigDecimal convertedDeposit;

    private String applicationCondition;

    @Column(nullable = false)
    private int displayOrder;

    private SupplyTarget(
            SupplyRow supplyRow,
            String target,
            String supplyRank,
            Integer supplyHouseholdCount,
            Integer reserveCount,
            BigDecimal rentalDeposit,
            BigDecimal monthlyRent,
            BigDecimal convertedDeposit,
            String applicationCondition,
            int displayOrder
    ) {
        validateRequired(supplyRow, "공급행");
        validateNotBlank(target, "대상");
        validateNotBlankIfPresent(supplyRank, "공급순위");
        validateNonNegativeIfPresent(supplyHouseholdCount, "공급세대수");
        validateNonNegativeIfPresent(reserveCount, "예비자수");
        validateAmountIfPresent(rentalDeposit, "임대보증금");
        validateAmountIfPresent(monthlyRent, "월임대료");
        validateAmountIfPresent(convertedDeposit, "전환보증금");
        validateNotBlankIfPresent(applicationCondition, "신청조건");
        validateNonNegative(displayOrder, "표시순서");
        this.supplyRow = supplyRow;
        this.target = target;
        this.supplyRank = supplyRank;
        this.supplyHouseholdCount = supplyHouseholdCount;
        this.reserveCount = reserveCount;
        this.rentalDeposit = rentalDeposit;
        this.monthlyRent = monthlyRent;
        this.convertedDeposit = convertedDeposit;
        this.applicationCondition = applicationCondition;
        this.displayOrder = displayOrder;
    }

    public static SupplyTarget create(
            SupplyRow supplyRow,
            String target,
            String supplyRank,
            Integer supplyHouseholdCount,
            Integer reserveCount,
            BigDecimal rentalDeposit,
            BigDecimal monthlyRent,
            BigDecimal convertedDeposit,
            String applicationCondition,
            int displayOrder
    ) {
        return new SupplyTarget(
                supplyRow,
                target,
                supplyRank,
                supplyHouseholdCount,
                reserveCount,
                rentalDeposit,
                monthlyRent,
                convertedDeposit,
                applicationCondition,
                displayOrder
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

    private void validateAmountIfPresent(BigDecimal value, String fieldName) {
        if (value != null) {
            validateNonNegativeAmount(value, fieldName);
            validateExactLong(value, fieldName);
        }
    }

    private void validateNonNegativeAmount(BigDecimal value, String fieldName) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }

    private void validateExactLong(BigDecimal value, String fieldName) {
        try {
            value.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(fieldName + "은 Long 범위의 정수여야 한다.", exception);
        }
    }
}
