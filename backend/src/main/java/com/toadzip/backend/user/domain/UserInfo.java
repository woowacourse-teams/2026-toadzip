package com.toadzip.backend.user.domain;

import static jakarta.persistence.FetchType.LAZY;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "user_infos")
@NoArgsConstructor(access = PROTECTED)
public class UserInfo {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDate birthDate;

    @Column(nullable = false)
    private String currentResidenceRegion;

    @Column(nullable = false)
    private boolean headOfHousehold;

    @Column(nullable = false)
    private int householdMemberCount;

    @Column(nullable = false)
    private boolean singleParentFamily;

    @Column(nullable = false)
    private boolean nonHomeowner;

    @Column(nullable = false)
    private String maritalStatus;

    private LocalDate marriageDate;

    private BigDecimal spouseMonthlyIncome;

    @Column(nullable = false)
    private int childCount;

    @Column(nullable = false)
    private boolean student;

    @Column(nullable = false)
    private boolean employed;

    @Column(nullable = false)
    private BigDecimal personalAverageMonthlyIncome;

    @Column(nullable = false)
    private BigDecimal householdAverageMonthlyIncome;

    @Column(nullable = false)
    private BigDecimal parentsAverageMonthlyIncome;

    @Column(nullable = false)
    private BigDecimal totalAssets;

    @Column(nullable = false)
    private BigDecimal vehicleValue;

    @Column(nullable = false)
    private boolean housingSubscriptionAccount;

    private LocalDate housingSubscriptionAccountJoinDate;

    private Integer housingSubscriptionPaymentCount;

    @Column(nullable = false)
    private boolean housingBenefitRecipient;

    @Column(nullable = false)
    private boolean basicLivingRecipient;

    @Column(nullable = false)
    private boolean disabled;

    @Column(nullable = false)
    private boolean veteran;

    private LocalDate newbornBirthDate;

    private UserInfo(
            User user,
            LocalDate birthDate,
            String currentResidenceRegion,
            boolean headOfHousehold,
            int householdMemberCount,
            boolean singleParentFamily,
            boolean nonHomeowner,
            String maritalStatus,
            LocalDate marriageDate,
            BigDecimal spouseMonthlyIncome,
            int childCount,
            boolean student,
            boolean employed,
            BigDecimal personalAverageMonthlyIncome,
            BigDecimal householdAverageMonthlyIncome,
            BigDecimal parentsAverageMonthlyIncome,
            BigDecimal totalAssets,
            BigDecimal vehicleValue,
            boolean housingSubscriptionAccount,
            LocalDate housingSubscriptionAccountJoinDate,
            Integer housingSubscriptionPaymentCount,
            boolean housingBenefitRecipient,
            boolean basicLivingRecipient,
            boolean disabled,
            boolean veteran,
            LocalDate newbornBirthDate
    ) {
        validateRequired(user, "유저");
        validateRequired(birthDate, "생년월일");
        validateNotBlank(currentResidenceRegion, "현재 거주지역");
        validateNonNegative(householdMemberCount, "가구원 수");
        validateNotBlank(maritalStatus, "혼인 상태");
        validateNonNegativeIfPresent(spouseMonthlyIncome, "배우자 소득");
        validateNonNegative(childCount, "자녀 수");
        validateRequiredAmount(personalAverageMonthlyIncome, "본인 월평균 소득");
        validateRequiredAmount(householdAverageMonthlyIncome, "가구 월평균 소득");
        validateRequiredAmount(parentsAverageMonthlyIncome, "부모 월평균 소득");
        validateRequiredAmount(totalAssets, "총자산");
        validateRequiredAmount(vehicleValue, "자동차 가액");
        validateNonNegativeIfPresent(housingSubscriptionPaymentCount, "청약통장 납입 횟수");
        this.user = user;
        this.birthDate = birthDate;
        this.currentResidenceRegion = currentResidenceRegion;
        this.headOfHousehold = headOfHousehold;
        this.householdMemberCount = householdMemberCount;
        this.singleParentFamily = singleParentFamily;
        this.nonHomeowner = nonHomeowner;
        this.maritalStatus = maritalStatus;
        this.marriageDate = marriageDate;
        this.spouseMonthlyIncome = spouseMonthlyIncome;
        this.childCount = childCount;
        this.student = student;
        this.employed = employed;
        this.personalAverageMonthlyIncome = personalAverageMonthlyIncome;
        this.householdAverageMonthlyIncome = householdAverageMonthlyIncome;
        this.parentsAverageMonthlyIncome = parentsAverageMonthlyIncome;
        this.totalAssets = totalAssets;
        this.vehicleValue = vehicleValue;
        this.housingSubscriptionAccount = housingSubscriptionAccount;
        this.housingSubscriptionAccountJoinDate = housingSubscriptionAccountJoinDate;
        this.housingSubscriptionPaymentCount = housingSubscriptionPaymentCount;
        this.housingBenefitRecipient = housingBenefitRecipient;
        this.basicLivingRecipient = basicLivingRecipient;
        this.disabled = disabled;
        this.veteran = veteran;
        this.newbornBirthDate = newbornBirthDate;
    }

    public static UserInfo create(
            User user,
            LocalDate birthDate,
            String currentResidenceRegion,
            boolean headOfHousehold,
            int householdMemberCount,
            boolean singleParentFamily,
            boolean nonHomeowner,
            String maritalStatus,
            LocalDate marriageDate,
            BigDecimal spouseMonthlyIncome,
            int childCount,
            boolean student,
            boolean employed,
            BigDecimal personalAverageMonthlyIncome,
            BigDecimal householdAverageMonthlyIncome,
            BigDecimal parentsAverageMonthlyIncome,
            BigDecimal totalAssets,
            BigDecimal vehicleValue,
            boolean housingSubscriptionAccount,
            LocalDate housingSubscriptionAccountJoinDate,
            Integer housingSubscriptionPaymentCount,
            boolean housingBenefitRecipient,
            boolean basicLivingRecipient,
            boolean disabled,
            boolean veteran,
            LocalDate newbornBirthDate
    ) {
        return new UserInfo(
                user,
                birthDate,
                currentResidenceRegion,
                headOfHousehold,
                householdMemberCount,
                singleParentFamily,
                nonHomeowner,
                maritalStatus,
                marriageDate,
                spouseMonthlyIncome,
                childCount,
                student,
                employed,
                personalAverageMonthlyIncome,
                householdAverageMonthlyIncome,
                parentsAverageMonthlyIncome,
                totalAssets,
                vehicleValue,
                housingSubscriptionAccount,
                housingSubscriptionAccountJoinDate,
                housingSubscriptionPaymentCount,
                housingBenefitRecipient,
                basicLivingRecipient,
                disabled,
                veteran,
                newbornBirthDate
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

    private void validateNonNegativeIfPresent(Integer value, String fieldName) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }
}
