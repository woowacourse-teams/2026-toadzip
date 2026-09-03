package com.toadzip.backend.housing.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.RentalType;

public record HousingComplexSearchCondition(
        MapBounds bounds,
        String keyword,
        String provinceCode,
        Set<String> cityCountyDistrictCodes,
        Set<RentalType> rentalTypes,
        Set<ApplicationStatus> applicationStatuses,
        Set<AgencyCode> agencyCodes,
        Set<RecruitmentType> recruitmentTypes,
        BigDecimal minDeposit,
        BigDecimal maxDeposit,
        BigDecimal minMonthlyRent,
        BigDecimal maxMonthlyRent,
        BigDecimal minExclusiveArea,
        BigDecimal maxExclusiveArea,
        Integer builtYearFrom,
        Integer builtYearTo,
        Boolean hasElevator,
        Boolean hasActiveAnnouncement,
        LocalDate today
) {
    public HousingComplexSearchCondition {
        cityCountyDistrictCodes = Set.copyOf(cityCountyDistrictCodes);
        rentalTypes = Set.copyOf(rentalTypes);
        applicationStatuses = Set.copyOf(applicationStatuses);
        agencyCodes = Set.copyOf(agencyCodes);
        recruitmentTypes = Set.copyOf(recruitmentTypes);
    }

    public HousingComplexSearchCondition(
            MapBounds bounds,
            String keyword,
            String provinceCode,
            Set<String> cityCountyDistrictCodes,
            Set<RentalType> rentalTypes,
            Set<ApplicationStatus> applicationStatuses,
            Set<AgencyCode> agencyCodes,
            Set<RecruitmentType> recruitmentTypes,
            BigDecimal minDeposit,
            BigDecimal maxDeposit,
            BigDecimal minMonthlyRent,
            BigDecimal maxMonthlyRent,
            BigDecimal minExclusiveArea,
            BigDecimal maxExclusiveArea,
            Integer builtYearFrom,
            Integer builtYearTo,
            Boolean hasElevator,
            LocalDate today
    ) {
        this(
                bounds, keyword, provinceCode, cityCountyDistrictCodes, rentalTypes, applicationStatuses,
                agencyCodes, recruitmentTypes, minDeposit, maxDeposit, minMonthlyRent, maxMonthlyRent,
                minExclusiveArea, maxExclusiveArea, builtYearFrom, builtYearTo, hasElevator,
                null, today
        );
    }
}
