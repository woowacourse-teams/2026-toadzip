package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.RentalType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
        long viewCount,
        int supplyComplexCount,
        Integer supplyHouseholdCount,
        AgencyResponse agency,
        BigDecimal actualCompetitionRate,
        BigDecimal predictedCompetitionRate,
        String thumbnailImageUrl
) {

    public AnnouncementListItemResponse {
        regionNames = List.copyOf(regionNames);
    }
}
