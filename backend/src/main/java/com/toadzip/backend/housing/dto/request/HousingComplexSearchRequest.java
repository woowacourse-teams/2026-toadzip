package com.toadzip.backend.housing.dto.request;

import java.math.BigDecimal;
import java.util.List;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.domain.SearchScope;

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
        BigDecimal southWestLat,
        BigDecimal southWestLng,
        BigDecimal northEastLat,
        BigDecimal northEastLng,
        Boolean hasActiveAnnouncement,
        SearchScope scope
) {
    public HousingComplexSearchRequest(
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
            BigDecimal southWestLat,
            BigDecimal southWestLng,
            BigDecimal northEastLat,
            BigDecimal northEastLng
    ) {
        this(
                keyword, regionCode, rentalTypes, applicationStatuses, agencyCodes, recruitmentTypes,
                minDeposit, maxDeposit, minMonthlyRent, maxMonthlyRent, minExclusiveArea, maxExclusiveArea,
                builtYearFrom, builtYearTo, hasElevator, southWestLat, southWestLng, northEastLat, northEastLng,
                null, null
        );
    }

    public HousingComplexSearchRequest(
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
            BigDecimal southWestLat,
            BigDecimal southWestLng,
            BigDecimal northEastLat,
            BigDecimal northEastLng,
            Boolean hasActiveAnnouncement
    ) {
        this(keyword, regionCode, rentalTypes, applicationStatuses, agencyCodes, recruitmentTypes,
                minDeposit, maxDeposit, minMonthlyRent, maxMonthlyRent, minExclusiveArea, maxExclusiveArea,
                builtYearFrom, builtYearTo, hasElevator, southWestLat, southWestLng, northEastLat, northEastLng,
                hasActiveAnnouncement, null);
    }

}
