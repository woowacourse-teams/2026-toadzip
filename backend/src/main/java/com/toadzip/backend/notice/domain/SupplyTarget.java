package com.toadzip.backend.notice.domain;

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

    @Column(nullable = false)
    private String supplyRank;

    @Column(nullable = false)
    private int supplyHouseholdCount;

    @Column(nullable = false)
    private int reserveCount;

    @Column(nullable = false)
    private BigDecimal rentalDeposit;

    @Column(nullable = false)
    private BigDecimal monthlyRent;

    private BigDecimal convertedDeposit;

    @Column(nullable = false)
    private String applicationCondition;

    @Column(nullable = false)
    private int displayOrder;

    private SupplyTarget(
            SupplyRow supplyRow,
            String target,
            String supplyRank,
            int supplyHouseholdCount,
            int reserveCount,
            BigDecimal rentalDeposit,
            BigDecimal monthlyRent,
            BigDecimal convertedDeposit,
            String applicationCondition,
            int displayOrder
    ) {
        validateRequired(supplyRow, "공급행");
        validateNotBlank(target, "대상");
        validateNotBlank(supplyRank, "공급순위");
        validateNonNegative(supplyHouseholdCount, "공급세대수");
        validateNonNegative(reserveCount, "예비자수");
        validateRequiredAmount(rentalDeposit, "임대보증금");
        validateRequiredAmount(monthlyRent, "월임대료");
        validateNonNegativeIfPresent(convertedDeposit, "전환보증금");
        validateNotBlank(applicationCondition, "신청조건");
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
            int supplyHouseholdCount,
            int reserveCount,
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

    private void validateRequiredAmount(BigDecimal value, String fieldName) {
        validateRequired(value, fieldName);
        validateNonNegativeIfPresent(value, fieldName);
    }

    private void validateNonNegativeIfPresent(BigDecimal value, String fieldName) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }
}
