package com.toadzip.backend.housing.dto.request;

import io.swagger.v3.oas.annotations.Parameter;
import java.math.BigDecimal;
import java.util.List;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;

public record HousingComplexSearchRequest(
        String keyword,
        String regionCode,
        List<RentalType> rentalTypes,
        List<ApplicationStatus> applicationStatuses,
        List<AgencyCode> agencyCodes,
        List<RecruitmentType> recruitmentTypes,
        Long minDeposit,
        Long maxDeposit,
        Long minMonthlyRent,
        Long maxMonthlyRent,
        BigDecimal minExclusiveArea,
        BigDecimal maxExclusiveArea,
        Integer builtYearFrom,
        Integer builtYearTo,
        Boolean hasElevator,
        @Parameter(required = true) BigDecimal southWestLat,
        @Parameter(required = true) BigDecimal southWestLng,
        @Parameter(required = true) BigDecimal northEastLat,
        @Parameter(required = true) BigDecimal northEastLng
) {
}
