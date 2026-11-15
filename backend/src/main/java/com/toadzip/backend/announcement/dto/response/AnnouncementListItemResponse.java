package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.RentalType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** supplyConfirmed가 false면 단지 수·모집수는 공고문으로 확인하기 전의 기관 준비 데이터다. */
public record AnnouncementListItemResponse(
        long announcementId,
        AnnouncementPublicationType publicationType,
        ApplicationStatus applicationStatus,
        RentalType rentalType,
        RecruitmentType recruitmentType,
        String title,
        List<String> regionNames,
        LocalDate publishedAt,
        LocalDate applicationStartAt,
        LocalDate applicationEndAt,
        Integer dDay,
        LocalDate winnerAnnouncementAt,
        long viewCount,
        int supplyComplexCount,
        Integer supplyHouseholdCount,
        AgencyResponse agency,
        BigDecimal actualCompetitionRate,
        BigDecimal predictedCompetitionRate,
        String thumbnailImageUrl,
        List<ApplicationScheduleResponse> applicationSchedules,
        boolean supplyConfirmed
) {

    public AnnouncementListItemResponse {
        regionNames = List.copyOf(regionNames);
        applicationSchedules = List.copyOf(applicationSchedules);
    }
}
