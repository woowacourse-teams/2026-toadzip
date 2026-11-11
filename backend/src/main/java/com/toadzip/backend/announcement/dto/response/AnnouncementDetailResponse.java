package com.toadzip.backend.announcement.dto.response;

import com.toadzip.backend.announcement.domain.AnnouncementListPhase;
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
        CompetitionResponse competition,
        List<ApplicationScheduleResponse> applicationSchedules,
        LhRevisionResponse revision,
        AnnouncementListPhase listPhase
) {

    public AnnouncementDetailResponse {
        if (listPhase == null) {
            listPhase = AnnouncementListPhase.NONE;
        }
        regionNames = List.copyOf(regionNames);
        targets = List.copyOf(targets);
        receptionPlaces = List.copyOf(receptionPlaces);
        schedules = List.copyOf(schedules);
        attachments = List.copyOf(attachments);
        supplyRows = List.copyOf(supplyRows);
        applicationSchedules = List.copyOf(applicationSchedules);
    }

    /** 목록 탭을 아직 판정하지 않은 응답. 판정은 조회 서비스가 {@link #withListPhase}로 채운다. */
    public AnnouncementDetailResponse(
            long announcementId, AnnouncementPublicationType publicationType, String correctionOrCancellationReason,
            ApplicationStatus applicationStatus, RentalType rentalType, RecruitmentType recruitmentType, String title,
            List<String> regionNames, AgencyResponse agency, LocalDate publishedAt, LocalDate applicationStartAt,
            LocalDate applicationEndAt, Integer dDay, LocalDate winnerAnnouncementAt, long viewCount,
            List<String> targets, int supplyComplexCount, Integer supplyHouseholdCount, String documentLinkUrl,
            List<ReceptionPlaceResponse> receptionPlaces, List<AnnouncementScheduleResponse> schedules,
            List<AnnouncementAttachmentResponse> attachments, List<SupplyRowResponse> supplyRows,
            CompetitionResponse competition, List<ApplicationScheduleResponse> applicationSchedules,
            LhRevisionResponse revision
    ) {
        this(announcementId, publicationType, correctionOrCancellationReason, applicationStatus, rentalType,
                recruitmentType, title, regionNames, agency, publishedAt, applicationStartAt, applicationEndAt, dDay,
                winnerAnnouncementAt, viewCount, targets, supplyComplexCount, supplyHouseholdCount, documentLinkUrl,
                receptionPlaces, schedules, attachments, supplyRows, competition, applicationSchedules, revision,
                AnnouncementListPhase.NONE);
    }

    public AnnouncementDetailResponse withListPhase(AnnouncementListPhase phase) {
        return new AnnouncementDetailResponse(announcementId, publicationType, correctionOrCancellationReason,
                applicationStatus, rentalType, recruitmentType, title, regionNames, agency, publishedAt,
                applicationStartAt, applicationEndAt, dDay, winnerAnnouncementAt, viewCount, targets,
                supplyComplexCount, supplyHouseholdCount, documentLinkUrl, receptionPlaces, schedules, attachments,
                supplyRows, competition, applicationSchedules, revision, phase);
    }
}
