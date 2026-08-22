package com.toadzip.backend.housing.domain;

import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "housing_complexes")
@NoArgsConstructor(access = PROTECTED)
public class HousingComplex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String sourceComplexIdentifier;

    @Column(nullable = false)
    private String supplyType;

    @Embedded
    private Address address;

    @Column(nullable = false)
    private int totalHouseholdCount;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private LocalDate completionDate;

    @Column(nullable = false)
    private String heatingType;

    @Column(nullable = false)
    private String housingType;

    @Column(nullable = false)
    private String corridorType;

    @Column(nullable = false)
    private boolean elevatorInstalled;

    @Column(nullable = false)
    private int parkingSpaceCount;

    private String imageUrl;

    private Integer recentOneYearMoveOutCount;

    private HousingComplex(
            String name,
            String sourceComplexIdentifier,
            String supplyType,
            Address address,
            int totalHouseholdCount,
            String provider,
            LocalDate completionDate,
            String heatingType,
            String housingType,
            String corridorType,
            boolean elevatorInstalled,
            int parkingSpaceCount,
            String imageUrl,
            Integer recentOneYearMoveOutCount
    ) {
        validateNotBlank(name, "단지명");
        validateNotBlank(sourceComplexIdentifier, "API 응답값 단지 식별자");
        validateNotBlank(supplyType, "공급유형");
        validateRequired(address, "주소");
        validateNonNegative(totalHouseholdCount, "전체 세대수");
        validateNotBlank(provider, "공급기관");
        validateRequired(completionDate, "준공일");
        validateNotBlank(heatingType, "난방유형");
        validateNotBlank(housingType, "주택유형");
        validateNotBlank(corridorType, "복도유형");
        validateNonNegative(parkingSpaceCount, "주차대수");
        validateNonNegativeIfPresent(recentOneYearMoveOutCount, "최근 1년 퇴거자 수");
        this.name = name;
        this.sourceComplexIdentifier = sourceComplexIdentifier;
        this.supplyType = supplyType;
        this.address = address;
        this.totalHouseholdCount = totalHouseholdCount;
        this.provider = provider;
        this.completionDate = completionDate;
        this.heatingType = heatingType;
        this.housingType = housingType;
        this.corridorType = corridorType;
        this.elevatorInstalled = elevatorInstalled;
        this.parkingSpaceCount = parkingSpaceCount;
        this.imageUrl = imageUrl;
        this.recentOneYearMoveOutCount = recentOneYearMoveOutCount;
    }

    public static HousingComplex create(
            String name,
            String sourceComplexIdentifier,
            String supplyType,
            Address address,
            int totalHouseholdCount,
            String provider,
            LocalDate completionDate,
            String heatingType,
            String housingType,
            String corridorType,
            boolean elevatorInstalled,
            int parkingSpaceCount,
            String imageUrl,
            Integer recentOneYearMoveOutCount
    ) {
        return new HousingComplex(
                name,
                sourceComplexIdentifier,
                supplyType,
                address,
                totalHouseholdCount,
                provider,
                completionDate,
                heatingType,
                housingType,
                corridorType,
                elevatorInstalled,
                parkingSpaceCount,
                imageUrl,
                recentOneYearMoveOutCount
        );
    }

    private void validateNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 필수다.");
        }
    }

    private void validateNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }

    private void validateNonNegativeIfPresent(Integer value, String fieldName) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(fieldName + "은 음수일 수 없다.");
        }
    }
}
