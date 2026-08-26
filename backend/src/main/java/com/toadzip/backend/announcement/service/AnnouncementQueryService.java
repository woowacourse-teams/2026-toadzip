package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.dto.response.AgencyResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementAttachmentResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListItemResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementScheduleResponse;
import com.toadzip.backend.announcement.dto.response.CompetitionResponse;
import com.toadzip.backend.announcement.dto.response.HousingTypeResponse;
import com.toadzip.backend.announcement.dto.response.ReceptionPlaceResponse;
import com.toadzip.backend.announcement.dto.response.SupplyComplexResponse;
import com.toadzip.backend.announcement.dto.response.SupplyRowResponse;
import com.toadzip.backend.announcement.dto.response.SupplyTargetResponse;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AnnouncementQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MINIMUM_PAGE_SIZE = 1;
    private static final int MAXIMUM_PAGE_SIZE = 50;

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementScheduleRepository announcementScheduleRepository;
    private final AnnouncementAttachmentRepository announcementAttachmentRepository;
    private final SupplyRowRepository supplyRowRepository;
    private final SupplyTargetRepository supplyTargetRepository;
    private final RegionCodeResolver regionCodeResolver;
    private final AnnouncementCursorCodec announcementCursorCodec;
    private final Clock clock;

    public AnnouncementQueryService(
            AnnouncementRepository announcementRepository,
            AnnouncementScheduleRepository announcementScheduleRepository,
            AnnouncementAttachmentRepository announcementAttachmentRepository,
            SupplyRowRepository supplyRowRepository,
            SupplyTargetRepository supplyTargetRepository,
            RegionCodeResolver regionCodeResolver,
            AnnouncementCursorCodec announcementCursorCodec,
            Clock clock
    ) {
        this.announcementRepository = announcementRepository;
        this.announcementScheduleRepository = announcementScheduleRepository;
        this.announcementAttachmentRepository = announcementAttachmentRepository;
        this.supplyRowRepository = supplyRowRepository;
        this.supplyTargetRepository = supplyTargetRepository;
        this.regionCodeResolver = regionCodeResolver;
        this.announcementCursorCodec = announcementCursorCodec;
        this.clock = clock;
    }

    public AnnouncementListResponse getAnnouncements(String cursor, int size) {
        validateSize(size);
        LocalDate today = currentSeoulDate();
        List<Announcement> fetchedAnnouncements = findLatestAnnouncements(cursor, size);
        boolean hasNext = fetchedAnnouncements.size() > size;
        List<Announcement> announcements = fetchedAnnouncements.stream().limit(size).toList();
        List<SupplyRow> supplyRows = findSupplyRows(announcementIds(announcements));
        Map<Long, List<SupplyRow>> rowsByAnnouncementId = groupRowsByAnnouncementId(supplyRows);
        List<AnnouncementListItemResponse> items = announcements.stream()
                .map(announcement -> toListItem(
                        announcement,
                        rowsByAnnouncementId.getOrDefault(announcement.getId(), List.of()),
                        today
                ))
                .toList();
        return new AnnouncementListResponse(items, nextCursor(announcements, hasNext), hasNext);
    }

    public AnnouncementDetailResponse getAnnouncement(long announcementId) {
        LocalDate today = currentSeoulDate();
        Announcement announcement = announcementRepository.findDetailById(announcementId)
                .orElseThrow(AnnouncementNotFoundException::new);
        List<Long> announcementIds = List.of(announcement.getId());
        List<AnnouncementSchedule> schedules = announcementScheduleRepository.findAllByAnnouncementIdIn(
                announcementIds
        );
        List<AnnouncementAttachment> attachments = announcementAttachmentRepository.findAllByAnnouncementIdIn(
                announcementIds
        );
        List<SupplyRow> supplyRows = findSupplyRows(announcementIds);
        List<SupplyTarget> supplyTargets = findSupplyTargets(supplyRowIds(supplyRows));
        Map<Long, List<SupplyTarget>> targetsBySupplyRowId = groupTargetsBySupplyRowId(supplyTargets);
        ListAggregate aggregate = aggregateRows(supplyRows);
        SupplyComposition supplyComposition = composeSupplyRows(supplyRows, targetsBySupplyRowId);
        return toDetailResponse(
                announcement,
                schedules,
                attachments,
                supplyComposition,
                aggregate,
                today
        );
    }

    private void validateSize(int size) {
        if (size < MINIMUM_PAGE_SIZE || size > MAXIMUM_PAGE_SIZE) {
            throw new InvalidAnnouncementRequestException();
        }
    }

    private LocalDate currentSeoulDate() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    private List<Announcement> findLatestAnnouncements(String cursor, int size) {
        Pageable pageable = PageRequest.of(0, size + 1, Sort.unsorted());
        if (cursor == null) {
            return announcementRepository.findLatestLeaves(pageable);
        }
        AnnouncementCursorCodec.AnnouncementCursor decodedCursor = announcementCursorCodec.decode(cursor);
        return announcementRepository.findLatestLeavesAfter(
                decodedCursor.postedDate(),
                decodedCursor.id(),
                pageable
        );
    }

    private String nextCursor(List<Announcement> announcements, boolean hasNext) {
        if (!hasNext) {
            return null;
        }
        Announcement lastAnnouncement = announcements.getLast();
        return announcementCursorCodec.encode(lastAnnouncement.getPostedDate(), lastAnnouncement.getId());
    }

    private List<Long> announcementIds(List<Announcement> announcements) {
        return announcements.stream().map(Announcement::getId).toList();
    }

    private List<Long> supplyRowIds(List<SupplyRow> supplyRows) {
        return supplyRows.stream().map(SupplyRow::getId).toList();
    }

    private List<SupplyRow> findSupplyRows(Collection<Long> announcementIds) {
        if (announcementIds.isEmpty()) {
            return List.of();
        }
        return supplyRowRepository.findAllByAnnouncementIdIn(announcementIds);
    }

    private List<SupplyTarget> findSupplyTargets(Collection<Long> supplyRowIds) {
        if (supplyRowIds.isEmpty()) {
            return List.of();
        }
        return supplyTargetRepository.findAllBySupplyRowIdIn(supplyRowIds);
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
                applicationStatus(announcement, today),
                announcement.getSupplyType(),
                announcement.getRecruitmentType(),
                announcement.getName(),
                aggregate.regionNames(),
                announcement.getPostedDate(),
                announcement.getApplicationStartDate(),
                announcement.getApplicationEndDate(),
                dDay(announcement, today),
                announcement.getViewCount(),
                aggregate.supplyComplexCount(),
                aggregate.supplyHouseholdCount(),
                agencyResponse(announcement.getProvider()),
                announcement.getActualCompetitionRate(),
                announcement.getPredictedCompetitionRate(),
                aggregate.thumbnailImageUrl()
        );
    }

    private AnnouncementDetailResponse toDetailResponse(
            Announcement announcement,
            List<AnnouncementSchedule> schedules,
            List<AnnouncementAttachment> attachments,
            SupplyComposition supplyComposition,
            ListAggregate aggregate,
            LocalDate today
    ) {
        return new AnnouncementDetailResponse(
                announcement.getId(),
                announcement.getStatus(),
                announcement.getCorrectionCancellationReason(),
                applicationStatus(announcement, today),
                announcement.getSupplyType(),
                announcement.getRecruitmentType(),
                announcement.getName(),
                aggregate.regionNames(),
                agencyResponse(announcement.getProvider()),
                announcement.getPostedDate(),
                announcement.getApplicationStartDate(),
                announcement.getApplicationEndDate(),
                dDay(announcement, today),
                announcement.getWinnerAnnouncementDate(),
                announcement.getViewCount(),
                supplyComposition.targets(),
                aggregate.supplyComplexCount(),
                aggregate.supplyHouseholdCount(),
                announcement.getOriginalUrl(),
                List.of(receptionPlaceResponse(announcement.getReceptionPlace())),
                schedules.stream().map(this::scheduleResponse).toList(),
                attachments.stream().map(this::attachmentResponse).toList(),
                supplyComposition.supplyRows(),
                new CompetitionResponse(
                        announcement.getActualCompetitionRate(),
                        announcement.getPredictedCompetitionRate()
                )
        );
    }

    private ApplicationStatus applicationStatus(Announcement announcement, LocalDate today) {
        if (announcement.getStatus() == AnnouncementPublicationType.CANCELLATION) {
            return ApplicationStatus.CANCELLED;
        }
        if (today.isBefore(announcement.getApplicationStartDate())) {
            return ApplicationStatus.BEFORE_APPLICATION;
        }
        if (!today.isAfter(announcement.getApplicationEndDate())) {
            return ApplicationStatus.APPLYING;
        }
        return ApplicationStatus.CLOSED;
    }

    private Integer dDay(Announcement announcement, LocalDate today) {
        if (announcement.getStatus() == AnnouncementPublicationType.CANCELLATION) {
            return null;
        }
        if (today.isAfter(announcement.getApplicationEndDate())) {
            return null;
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(today, announcement.getApplicationEndDate()));
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
