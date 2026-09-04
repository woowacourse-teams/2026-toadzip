package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
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
import java.util.HashMap;
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

    List<AnnouncementListItemResponse> toListItemResponses(
            List<Announcement> announcements,
            List<SupplyRow> supplyRows,
            LocalDate today
    ) {
        Map<Long, List<SupplyRow>> rowsByAnnouncementId = groupRowsByAnnouncementId(supplyRows);
        return announcements.stream()
                .map(announcement -> toListItem(
                        announcement,
                        rowsByAnnouncementId.getOrDefault(announcement.getId(), List.of()),
                        today
                ))
                .toList();
    }

    AnnouncementDetailResponse toDetailResponse(
            Announcement announcement,
            List<AnnouncementSchedule> schedules,
            List<AnnouncementAttachment> attachments,
            List<SupplyRow> supplyRows,
            List<SupplyTarget> supplyTargets,
            LocalDate today
    ) {
        Map<Long, List<SupplyTarget>> targetsBySupplyRowId = groupTargetsBySupplyRowId(supplyTargets);
        ListAggregate aggregate = aggregateRows(supplyRows);
        SupplyComposition supplyComposition = composeSupplyRows(supplyRows, targetsBySupplyRowId);
        return new AnnouncementDetailResponse(
                announcement.getId(),
                announcement.getStatus(),
                announcement.getCorrectionCancellationReason(),
                applicationStatusCalculator.calculateApplicationStatus(announcement, today),
                announcement.getSupplyType(),
                announcement.getRecruitmentType(),
                announcement.getName(),
                aggregate.regionNames(),
                agencyResponse(announcement.getProvider()),
                announcement.getPostedDate(),
                announcement.getApplicationStartDate(),
                announcement.getApplicationEndDate(),
                applicationStatusCalculator.calculateDDay(announcement, today),
                announcement.getWinnerAnnouncementDate(),
                announcement.getViewCount(),
                supplyComposition.targets(),
                aggregate.supplyComplexCount(),
                aggregate.supplyHouseholdCount(),
                announcement.getOriginalUrl(),
                receptionPlaceResponses(announcement.getReceptionPlace()),
                schedules.stream().map(this::scheduleResponse).toList(),
                attachments.stream().map(this::attachmentResponse).toList(),
                supplyComposition.supplyRows(),
                new CompetitionResponse(
                        announcement.getActualCompetitionRate(),
                        announcement.getPredictedCompetitionRate()
                )
        );
    }

    private Map<Long, List<SupplyRow>> groupRowsByAnnouncementId(List<SupplyRow> supplyRows) {
        Map<Long, List<SupplyRow>> rowsByAnnouncementId = new LinkedHashMap<>();
        for (SupplyRow supplyRow : supplyRows) {
            rowsByAnnouncementId.computeIfAbsent(
                    supplyRow.getAnnouncement().getId(),
                    ignored -> new ArrayList<>()
            ).add(supplyRow);
        }
        return rowsByAnnouncementId;
    }

    private Map<Long, List<SupplyTarget>> groupTargetsBySupplyRowId(List<SupplyTarget> supplyTargets) {
        Map<Long, List<SupplyTarget>> targetsBySupplyRowId = new HashMap<>();
        for (SupplyTarget supplyTarget : supplyTargets) {
            targetsBySupplyRowId.computeIfAbsent(
                    supplyTarget.getSupplyRow().getId(),
                    ignored -> new ArrayList<>()
            ).add(supplyTarget);
        }
        return targetsBySupplyRowId;
    }

    private AnnouncementListItemResponse toListItem(
            Announcement announcement,
            List<SupplyRow> supplyRows,
            LocalDate today
    ) {
        ListAggregate aggregate = aggregateRows(supplyRows);
        return new AnnouncementListItemResponse(
                announcement.getId(),
                announcement.getStatus(),
                applicationStatusCalculator.calculateApplicationStatus(announcement, today),
                announcement.getSupplyType(),
                announcement.getRecruitmentType(),
                announcement.getName(),
                aggregate.regionNames(),
                announcement.getPostedDate(),
                announcement.getApplicationStartDate(),
                announcement.getApplicationEndDate(),
                applicationStatusCalculator.calculateDDay(announcement, today),
                announcement.getViewCount(),
                aggregate.supplyComplexCount(),
                aggregate.supplyHouseholdCount(),
                agencyResponse(announcement.getProvider()),
                announcement.getActualCompetitionRate(),
                announcement.getPredictedCompetitionRate(),
                aggregate.thumbnailImageUrl()
        );
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
                receptionPlace.getUrl()
        );
    }

    private List<ReceptionPlaceResponse> receptionPlaceResponses(ReceptionPlace receptionPlace) {
        if (receptionPlace == null) {
            return List.of();
        }
        return List.of(receptionPlaceResponse(receptionPlace));
    }

    private AnnouncementScheduleResponse scheduleResponse(AnnouncementSchedule schedule) {
        return new AnnouncementScheduleResponse(
                schedule.getId(),
                schedule.getScheduleType(),
                schedule.getName(),
                schedule.getStartAt(),
                schedule.getEndAt()
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

    private SupplyComposition composeSupplyRows(
            List<SupplyRow> supplyRows,
            Map<Long, List<SupplyTarget>> targetsBySupplyRowId
    ) {
        List<SupplyRowResponse> responses = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();
        for (SupplyRow supplyRow : supplyRows) {
            List<SupplyTarget> rowTargets = targetsBySupplyRowId.getOrDefault(supplyRow.getId(), List.of());
            List<SupplyTargetResponse> targetResponses = rowTargets.stream()
                    .map(this::supplyTargetResponse)
                    .toList();
            responses.add(supplyRowResponse(supplyRow, targetResponses));
            rowTargets.stream().map(SupplyTarget::getTarget).forEach(targets::add);
        }
        return new SupplyComposition(List.copyOf(responses), List.copyOf(targets));
    }

    private SupplyRowResponse supplyRowResponse(
            SupplyRow supplyRow,
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
                supplyRow.getTotalSupplyHouseholdCount(),
                targetResponses
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

    private SupplyTargetResponse supplyTargetResponse(SupplyTarget supplyTarget) {
        return new SupplyTargetResponse(
                supplyTarget.getId(),
                supplyTarget.getTarget(),
                supplyTarget.getSupplyRank(),
                supplyTarget.getSupplyHouseholdCount(),
                supplyTarget.getReserveCount(),
                exactLong(supplyTarget.getRentalDeposit()),
                exactLong(supplyTarget.getMonthlyRent()),
                exactLong(supplyTarget.getConvertedDeposit()),
                supplyTarget.getApplicationCondition()
        );
    }

    private Long exactLong(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.longValueExact();
    }

    private ListAggregate aggregateRows(List<SupplyRow> supplyRows) {
        Set<String> regionNames = new LinkedHashSet<>();
        Set<Long> complexIds = new HashSet<>();
        Integer supplyHouseholdCount = null;
        String thumbnailImageUrl = null;
        for (SupplyRow supplyRow : supplyRows) {
            supplyHouseholdCount = addHouseholdCount(
                    supplyHouseholdCount,
                    supplyRow.getTotalSupplyHouseholdCount()
            );
            HousingComplex housingComplex = supplyRow.getHousingComplex();
            if (housingComplex == null) {
                continue;
            }
            complexIds.add(housingComplex.getId());
            addRegionName(regionNames, housingComplex.getAddress());
            if (thumbnailImageUrl == null && housingComplex.getImageUrl() != null) {
                thumbnailImageUrl = housingComplex.getImageUrl();
            }
        }
        return new ListAggregate(
                List.copyOf(regionNames),
                complexIds.size(),
                supplyHouseholdCount,
                thumbnailImageUrl
        );
    }

    private Integer addHouseholdCount(Integer total, Integer rowCount) {
        if (rowCount == null) {
            return total;
        }
        if (total == null) {
            return rowCount;
        }
        return Math.addExact(total, rowCount);
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
