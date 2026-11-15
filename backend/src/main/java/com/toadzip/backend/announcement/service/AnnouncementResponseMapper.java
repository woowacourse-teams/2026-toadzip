package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.dto.response.ApplicationScheduleResponse;
import com.toadzip.backend.announcement.dto.response.LhRevisionResponse;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.domain.LeaseTerms;
import com.toadzip.backend.announcement.domain.SupplyQuota;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowTarget;
import com.toadzip.backend.announcement.domain.SupplySheet;
import com.toadzip.backend.announcement.dto.response.AgencyResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementAttachmentResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListItemResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementScheduleResponse;
import com.toadzip.backend.announcement.dto.response.CompetitionResponse;
import com.toadzip.backend.announcement.dto.response.HousingTypeResponse;
import com.toadzip.backend.announcement.dto.response.ReceptionPlaceResponse;
import com.toadzip.backend.announcement.dto.response.SupplyComplexResponse;
import com.toadzip.backend.announcement.dto.response.SupplyRowResponse;
import com.toadzip.backend.announcement.dto.response.SupplyTargetResponse;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
final class AnnouncementResponseMapper {

    private final RegionCodeResolver regionCodeResolver;
    private final AnnouncementApplicationStatusCalculator applicationStatusCalculator;

    AnnouncementResponseMapper(
            RegionCodeResolver regionCodeResolver,
            AnnouncementApplicationStatusCalculator applicationStatusCalculator
    ) {
        this.regionCodeResolver = regionCodeResolver;
        this.applicationStatusCalculator = applicationStatusCalculator;
    }

    /** linkedComplexes는 공고별 연결 단지(공고↔단지 연결 규칙)다. 지역·단지 수·대표 이미지는 연결 단지로 정한다. */
    List<AnnouncementListItemResponse> toListItemResponses(
            List<Announcement> announcements,
            List<SupplyRow> supplyRows,
            Map<Long, List<HousingComplex>> linkedComplexes,
            SupplySheet sheet,
            List<AnnouncementApplicationSchedule> applicationSchedules,
            List<AnnouncementSchedule> schedules,
            LocalDate today
    ) {
        Map<Long, List<SupplyRow>> rowsByAnnouncementId = groupRowsByAnnouncementId(supplyRows);
        return announcements.stream()
                .map(announcement -> toListItem(
                        announcement,
                        rowsByAnnouncementId.getOrDefault(announcement.getId(), List.of()),
                        linkedComplexes.getOrDefault(announcement.getId(), List.of()),
                        sheet,
                        applicationSchedules.stream().filter(schedule ->
                                schedule.getAnnouncement().getId().equals(announcement.getId())).toList(),
                        resultAnnouncementDate(announcement, schedules),
                        today
                ))
                .toList();
    }

    AnnouncementDetailResponse toDetailResponse(
            Announcement announcement,
            List<AnnouncementSchedule> schedules,
            List<AnnouncementAttachment> attachments,
            List<SupplyRow> supplyRows,
            List<HousingComplex> linkedComplexes,
            SupplySheet sheet,
            List<AnnouncementApplicationSchedule> applicationSchedules,
            LocalDate today
    ) {
        ListAggregate aggregate = aggregate(supplyRows, linkedComplexes, sheet);
        SupplyComposition supplyComposition = composeSupplyRows(supplyRows, sheet);
        return new AnnouncementDetailResponse(
                announcement.getId(),
                announcement.getStatus(),
                announcement.getCorrectionCancellationReason(),
                applicationStatusCalculator.calculateApplicationStatus(announcement, applicationSchedules, today),
                announcement.getSupplyType(),
                announcement.getRecruitmentType(),
                announcement.getName(),
                aggregate.regionNames(),
                agencyResponse(announcement.getProvider()),
                announcement.getPostedDate(),
                announcement.getApplicationStartDate(),
                announcement.getApplicationEndDate(),
                applicationStatusCalculator.calculateDDay(announcement, applicationSchedules, today),
                announcement.getWinnerAnnouncementDate(),
                announcement.getViewCount(),
                supplyComposition.targets(),
                aggregate.supplyComplexCount(),
                aggregate.supplyHouseholdCount(),
                announcement.getOriginalUrl(),
                announcement.getReceptionPlaces().stream().map(this::receptionPlaceResponse).toList(),
                schedules.stream().map(this::scheduleResponse).toList(),
                attachments.stream().map(this::attachmentResponse).toList(),
                supplyComposition.supplyRows(),
                new CompetitionResponse(
                        announcement.getActualCompetitionRate(),
                        announcement.getPredictedCompetitionRate()
                ),
                applicationSchedules.stream().map(this::applicationScheduleResponse).toList(),
                revisionResponse(announcement)
        );
    }

    private Map<Long, List<SupplyRow>> groupRowsByAnnouncementId(List<SupplyRow> supplyRows) {
        Map<Long, List<SupplyRow>> rowsByAnnouncementId = new LinkedHashMap<>();
        for (SupplyRow supplyRow : supplyRows) {
            if (supplyRow.isAdminExcluded()) { continue; }
            rowsByAnnouncementId.computeIfAbsent(
                    supplyRow.getAnnouncement().getId(),
                    ignored -> new ArrayList<>()
            ).add(supplyRow);
        }
        return rowsByAnnouncementId;
    }

    private AnnouncementListItemResponse toListItem(
            Announcement announcement,
            List<SupplyRow> supplyRows,
            List<HousingComplex> linkedComplexes,
            SupplySheet sheet,
            List<AnnouncementApplicationSchedule> applicationSchedules,
            LocalDate resultAnnouncementDate,
            LocalDate today
    ) {
        ListAggregate aggregate = aggregate(supplyRows, linkedComplexes, sheet);
        LocalDate start = announcement.getApplicationStartDate();
        LocalDate end = announcement.getApplicationEndDate();
        if (announcement.isApplicationScheduleReviewed()) {
            var displaySchedule = applicationStatusCalculator.displaySchedule(applicationSchedules, today);
            start = displaySchedule.map(AnnouncementApplicationSchedule::getStartDate).orElse(null);
            end = displaySchedule.map(AnnouncementApplicationSchedule::getEndDate).orElse(null);
        }
        return new AnnouncementListItemResponse(
                announcement.getId(),
                announcement.getStatus(),
                applicationStatusCalculator.calculateApplicationStatus(announcement, applicationSchedules, today),
                announcement.getSupplyType(),
                announcement.getRecruitmentType(),
                announcement.getName(),
                aggregate.regionNames(),
                announcement.getPostedDate(),
                start,
                end,
                applicationStatusCalculator.calculateDDay(announcement, applicationSchedules, today),
                resultAnnouncementDate,
                announcement.getViewCount(),
                aggregate.supplyComplexCount(),
                aggregate.supplyHouseholdCount(),
                agencyResponse(announcement.getProvider()),
                announcement.getActualCompetitionRate(),
                announcement.getPredictedCompetitionRate(),
                aggregate.thumbnailImageUrl(),
                applicationSchedules.stream().map(this::applicationScheduleResponse).toList()
        );
    }

    // 당첨자 발표 일정이 공고의 발표일보다 늦게 잡혀 있으면 그 일정을 결과 발표일로 본다.
    private LocalDate resultAnnouncementDate(Announcement announcement, List<AnnouncementSchedule> schedules) {
        LocalDate result = announcement.getWinnerAnnouncementDate();
        for (AnnouncementSchedule schedule : schedules) {
            if (!schedule.getAnnouncement().getId().equals(announcement.getId())
                    || schedule.getScheduleType() != ScheduleType.WINNER_ANNOUNCEMENT) {
                continue;
            }
            LocalDate scheduled = schedule.getEndAt().toLocalDate();
            if (result == null || scheduled.isAfter(result)) {
                result = scheduled;
            }
        }
        return result;
    }

    private ApplicationScheduleResponse applicationScheduleResponse(AnnouncementApplicationSchedule schedule) {
        Long complexId = null;
        String complexName = null;
        if (schedule.getHousingComplex() != null) {
            complexId = schedule.getHousingComplex().getId();
            complexName = schedule.getHousingComplex().getName();
        }
        return new ApplicationScheduleResponse(schedule.getId(), complexId, complexName, schedule.getSupplyRank(),
                schedule.getState(), schedule.getCondition(), schedule.getStartDate(), schedule.getEndDate(),
                schedule.getStartTime(), schedule.getEndTime(), schedule.getSourceUrl(), schedule.getSourcePage());
    }

    private LhRevisionResponse revisionResponse(Announcement announcement) {
        if (announcement.getRevisionEvidenceUrl() == null) {
            return null;
        }
        return new LhRevisionResponse(announcement.getPreviousAnnouncement().getId(),
                announcement.getPreviousLhPanId(), announcement.getLhPanId(), announcement.getRevisionEvidenceUrl());
    }

    private AgencyResponse agencyResponse(AgencyCode agencyCode) {
        return new AgencyResponse(agencyCode, agencyCode.displayName());
    }

    private ReceptionPlaceResponse receptionPlaceResponse(ReceptionPlace receptionPlace) {
        return new ReceptionPlaceResponse(
                receptionPlace.getName(),
                receptionPlace.getMethod(),
                receptionPlace.getAddress(),
                receptionPlace.getContact(),
                receptionPlace.getUrl(),
                receptionPlace.getGuidance()
        );
    }

    private AnnouncementScheduleResponse scheduleResponse(AnnouncementSchedule schedule) {
        return new AnnouncementScheduleResponse(
                schedule.getId(),
                schedule.getScheduleType(),
                schedule.getName(),
                schedule.getStartAt(),
                schedule.getEndAt(),
                schedule.getComplexName()
        );
    }

    private AnnouncementAttachmentResponse attachmentResponse(AnnouncementAttachment attachment) {
        return new AnnouncementAttachmentResponse(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getFileType(),
                attachment.getFileUrl()
        );
    }

    private SupplyComposition composeSupplyRows(List<SupplyRow> supplyRows, SupplySheet sheet) {
        List<SupplyRowResponse> responses = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();
        for (SupplyRow supplyRow : supplyRows) {
            if (supplyRow.isAdminExcluded()) { continue; }
            // 대상 구분이 없는 표의 '전체' 칸은 대상이 아니다. 금액이나 따로 센 수가 있을 때만 그 값을 보이려고 남긴다.
            List<SupplyRowTarget> cells = sheet.cells(supplyRow.getId()).stream()
                    .filter(cell -> !cell.getTarget().everyone() || carriesValues(cell, sheet))
                    .toList();
            List<SupplyTargetResponse> targetResponses = cells.stream()
                    .map(cell -> supplyTargetResponse(cell, sheet))
                    .toList();
            responses.add(supplyRowResponse(supplyRow, sheet, targetResponses));
            cells.stream().filter(cell -> !cell.getTarget().everyone())
                    .map(cell -> cell.getTarget().getLabel()).forEach(targets::add);
        }
        return new SupplyComposition(List.copyOf(responses), List.copyOf(targets));
    }

    private SupplyRowResponse supplyRowResponse(
            SupplyRow supplyRow,
            SupplySheet sheet,
            List<SupplyTargetResponse> targetResponses
    ) {
        return new SupplyRowResponse(
                supplyRow.getId(),
                supplyRow.getSourceComplexName(),
                supplyRow.getSourceHousingTypeName(),
                supplyComplexResponse(supplyRow.getHousingComplex()),
                housingTypeResponse(supplyRow.getHousingType()),
                supplyRow.getExpectedMoveInMonth(),
                supplyRow.getSupplyCategory().toSupplyType(),
                sheet.rowCount(supplyRow.getId(), SupplyQuota.Kind.WINNER),
                targetResponses,
                sheet.sharedAcrossRows(supplyRow.getId(), SupplyQuota.Kind.WINNER)
                        .map(quota -> new SupplyRowResponse.SharedRecruitment(quota.getId(), quota.getHouseholdCount()))
                        .orElse(null),
                supplyRow.effectiveExclusiveArea(), supplyRow.effectiveSupplyArea(), supplyRow.isAdminModified(),
                supplyRow.displayHousingTypeName()
        );
    }

    private SupplyComplexResponse supplyComplexResponse(HousingComplex housingComplex) {
        if (housingComplex == null) {
            return null;
        }
        return new SupplyComplexResponse(
                housingComplex.getId(),
                housingComplex.getName(),
                housingComplex.getAddress().getRoadAddress(),
                housingComplex.getTotalHouseholdCount(),
                housingComplex.getImageUrl()
        );
    }

    private HousingTypeResponse housingTypeResponse(HousingType housingType) {
        if (housingType == null) {
            return null;
        }
        return new HousingTypeResponse(
                housingType.getId(),
                housingType.getName(),
                housingType.getExclusiveArea(),
                housingType.getSupplyArea(),
                housingType.getFloorPlanUrl(),
                null
        );
    }

    private SupplyTargetResponse supplyTargetResponse(SupplyRowTarget cell, SupplySheet sheet) {
        LeaseTerms.Amounts amounts = sheet.lease(cell).map(LeaseTerms::amounts)
                .orElse(new LeaseTerms.Amounts(null, null, null));
        return new SupplyTargetResponse(
                cell.getId(),
                cell.getTarget().getLabel(),
                null,
                sheet.ownCount(cell, SupplyQuota.Kind.WINNER),
                sheet.ownCount(cell, SupplyQuota.Kind.RESERVE),
                exactLong(amounts.rentalDeposit()),
                exactLong(amounts.monthlyRent()),
                exactLong(amounts.convertedDeposit()),
                blankToNull(cell.getTarget().getApplicationCondition()),
                sharedCount(sheet, cell, SupplyQuota.Kind.WINNER),
                sharedCount(sheet, cell, SupplyQuota.Kind.RESERVE)
        );
    }

    private SupplyTargetResponse.SharedCount sharedCount(SupplySheet sheet, SupplyRowTarget cell, SupplyQuota.Kind kind) {
        return sheet.sharedInRow(cell, kind)
                .map(shared -> new SupplyTargetResponse.SharedCount(shared.count(), shared.members().stream()
                        .map(member -> member.getTarget().getLabel()).toList()))
                .orElse(null);
    }

    private static boolean carriesValues(SupplyRowTarget cell, SupplySheet sheet) {
        return sheet.lease(cell).isPresent() || sheet.ownCount(cell, SupplyQuota.Kind.RESERVE) != null
                || sheet.ownCount(cell, SupplyQuota.Kind.WINNER) != null && sheet.cells(cell.getSupplyRow().getId()).size() > 1;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Long exactLong(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.longValueExact();
    }

    private ListAggregate aggregate(List<SupplyRow> supplyRows, List<HousingComplex> linkedComplexes,
            SupplySheet sheet) {
        Set<String> regionNames = new LinkedHashSet<>();
        String thumbnailImageUrl = null;
        for (HousingComplex housingComplex : linkedComplexes) {
            addRegionName(regionNames, housingComplex.getAddress());
            if (thumbnailImageUrl == null && housingComplex.getImageUrl() != null) {
                thumbnailImageUrl = housingComplex.getImageUrl();
            }
        }
        Set<Long> visibleRows = new HashSet<>();
        supplyRows.stream().filter(row -> !row.isAdminExcluded()).forEach(row -> visibleRows.add(row.getId()));
        return new ListAggregate(
                List.copyOf(regionNames),
                linkedComplexes.size(),
                sheet.total(SupplyQuota.Kind.WINNER, visibleRows::contains),
                thumbnailImageUrl
        );
    }

    private void addRegionName(Set<String> regionNames, Address address) {
        regionCodeResolver.resolve(
                address.getProvinceCode(),
                address.getCityCountyDistrictCode()
        ).ifPresent(regionNames::add);
    }

    private record ListAggregate(
            List<String> regionNames,
            int supplyComplexCount,
            Integer supplyHouseholdCount,
            String thumbnailImageUrl
    ) {
    }

    private record SupplyComposition(List<SupplyRowResponse> supplyRows, List<String> targets) {
    }
}
