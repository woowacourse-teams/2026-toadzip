package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.RentalType;
import java.time.LocalDate;
import java.util.List;

public record AnnouncementDetailResponse(
        long announcementId,
        AnnouncementPublicationType publicationType,
        String correctionOrCancellationReason,
        ApplicationStatus applicationStatus,
        RentalType rentalType,
        RecruitmentType recruitmentType,
        String title,
        List<String> regionNames,
        AgencyResponse agency,
        LocalDate publishedAt,
        LocalDate applicationStartAt,
        LocalDate applicationEndAt,
        Integer dDay,
        LocalDate winnerAnnouncementAt,
        long viewCount,
        List<String> targets,
        int supplyComplexCount,
        Integer supplyHouseholdCount,
        String documentLinkUrl,
        List<ReceptionPlaceResponse> receptionPlaces,
        List<AnnouncementScheduleResponse> schedules,
        List<AnnouncementAttachmentResponse> attachments,
        List<SupplyRowResponse> supplyRows,
        CompetitionResponse competition
) {

    public AnnouncementDetailResponse {
        regionNames = List.copyOf(regionNames);
        targets = List.copyOf(targets);
        receptionPlaces = List.copyOf(receptionPlaces);
        schedules = List.copyOf(schedules);
        attachments = List.copyOf(attachments);
        supplyRows = List.copyOf(supplyRows);
    }
}
