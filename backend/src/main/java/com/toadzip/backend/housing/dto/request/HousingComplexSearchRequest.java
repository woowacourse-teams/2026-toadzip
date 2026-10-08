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
        @Parameter(required = true, description = "지도 조회 필수. 목록 조회는 regionCode 지정 시 네 좌표 모두 생략 가능") BigDecimal southWestLat,
        @Parameter(required = true, description = "지도 조회 필수. 목록 조회는 regionCode 지정 시 네 좌표 모두 생략 가능") BigDecimal southWestLng,
        @Parameter(required = true, description = "지도 조회 필수. 목록 조회는 regionCode 지정 시 네 좌표 모두 생략 가능") BigDecimal northEastLat,
        @Parameter(required = true, description = "지도 조회 필수. 목록 조회는 regionCode 지정 시 네 좌표 모두 생략 가능") BigDecimal northEastLng,
        Boolean hasActiveAnnouncement
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
                null
        );
    }
}
