package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.dto.request.AnnouncementSearchRequest;
import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListItemResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListResponse;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.exception.InvalidRegionCodeException;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.AnnouncementSearchCondition;
import com.toadzip.backend.announcement.repository.AnnouncementSearchRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AnnouncementQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int MINIMUM_PAGE_SIZE = 1;
    private static final int MAXIMUM_PAGE_SIZE = 50;

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementSearchRepository announcementSearchRepository;
    private final AnnouncementScheduleRepository announcementScheduleRepository;
    private final AnnouncementAttachmentRepository announcementAttachmentRepository;
    private final SupplyRowRepository supplyRowRepository;
    private final SupplyTargetRepository supplyTargetRepository;
    private final AnnouncementResponseMapper announcementResponseMapper;
    private final AnnouncementCursorCodec announcementCursorCodec;
    private final Clock clock;
    private final RegionCodeResolver regionCodeResolver;

    public AnnouncementQueryService(
            AnnouncementRepository announcementRepository,
            AnnouncementSearchRepository announcementSearchRepository,
            AnnouncementScheduleRepository announcementScheduleRepository,
            AnnouncementAttachmentRepository announcementAttachmentRepository,
            SupplyRowRepository supplyRowRepository,
            SupplyTargetRepository supplyTargetRepository,
            AnnouncementResponseMapper announcementResponseMapper,
            AnnouncementCursorCodec announcementCursorCodec,
            Clock clock,
            RegionCodeResolver regionCodeResolver
    ) {
        this.announcementRepository = announcementRepository;
        this.announcementSearchRepository = announcementSearchRepository;
        this.announcementScheduleRepository = announcementScheduleRepository;
        this.announcementAttachmentRepository = announcementAttachmentRepository;
        this.supplyRowRepository = supplyRowRepository;
        this.supplyTargetRepository = supplyTargetRepository;
        this.announcementResponseMapper = announcementResponseMapper;
        this.announcementCursorCodec = announcementCursorCodec;
        this.clock = clock;
        this.regionCodeResolver = regionCodeResolver;
    }

    public AnnouncementListResponse getAnnouncements(AnnouncementSearchRequest request, String cursor, int size) {
        validateSize(size);
        LocalDate today = currentSeoulDate();
        AnnouncementSearchCondition condition = searchCondition(request, today);
        AnnouncementCursorCodec.AnnouncementCursor decodedCursor = decodeCursor(cursor);
        List<Announcement> fetchedAnnouncements = announcementSearchRepository.findLatestLeaves(
                condition, cursorPostedDate(decodedCursor), cursorId(decodedCursor), size + 1
        );
        boolean hasNext = fetchedAnnouncements.size() > size;
        List<Announcement> announcements = fetchedAnnouncements.stream().limit(size).toList();
        List<SupplyRow> supplyRows = findSupplyRows(announcementIds(announcements));
        List<AnnouncementListItemResponse> items = announcementResponseMapper.toListItemResponses(
                announcements,
                supplyRows,
                today
        );
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
        return announcementResponseMapper.toDetailResponse(
                announcement,
                schedules,
                attachments,
                supplyRows,
                supplyTargets,
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

    private AnnouncementSearchCondition searchCondition(AnnouncementSearchRequest request, LocalDate today) {
        if (request == null) {
            throw new InvalidAnnouncementRequestException();
        }
        String keyword = normalizedKeyword(request.keyword());
        validateApplicationPeriod(request.applicationFrom(), request.applicationTo());
        validatePublicationTypes(request.publicationTypes());
        validateApplicationStatuses(request.applicationStatuses());
        return new AnnouncementSearchCondition(
                keyword, regionCodes(request.regionCode()), normalizedValues(request.rentalTypes()),
                normalizedValues(request.applicationStatuses()), normalizedValues(request.publicationTypes()),
                normalizedValues(request.agencyCodes()), normalizedValues(request.recruitmentTypes()),
                request.applicationFrom(), request.applicationTo(), today
        );
    }

    private String normalizedKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalizedKeyword = keyword.trim();
        if (normalizedKeyword.isEmpty()) {
            throw new InvalidAnnouncementRequestException();
        }
        return normalizedKeyword;
    }

    private void validateApplicationPeriod(LocalDate applicationFrom, LocalDate applicationTo) {
        if (applicationFrom != null && applicationTo != null && applicationFrom.isAfter(applicationTo)) {
            throw new InvalidAnnouncementRequestException();
        }
    }

    private void validatePublicationTypes(List<AnnouncementPublicationType> publicationTypes) {
        if (publicationTypes != null && publicationTypes.contains(AnnouncementPublicationType.CANCELLATION)) {
            throw new InvalidAnnouncementRequestException();
        }
    }

    private void validateApplicationStatuses(List<ApplicationStatus> applicationStatuses) {
        if (applicationStatuses != null && applicationStatuses.contains(ApplicationStatus.CANCELLED)) {
            throw new InvalidAnnouncementRequestException();
        }
    }

    private Set<String> regionCodes(String regionCode) {
        if (regionCode == null) {
            return Set.of();
        }
        String normalizedRegionCode = regionCode.trim();
        if (normalizedRegionCode.isEmpty()) {
            throw new InvalidRegionCodeException();
        }
        return regionCodeResolver.equivalentCodes(normalizedRegionCode)
                .orElseThrow(InvalidRegionCodeException::new);
    }

    private <T> Set<T> normalizedValues(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        if (values.stream().anyMatch(value -> value == null)) {
            throw new InvalidAnnouncementRequestException();
        }
        return Set.copyOf(values);
    }

    private AnnouncementCursorCodec.AnnouncementCursor decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        return announcementCursorCodec.decode(cursor);
    }

    private LocalDate cursorPostedDate(AnnouncementCursorCodec.AnnouncementCursor cursor) {
        if (cursor == null) {
            return null;
        }
        return cursor.postedDate();
    }

    private Long cursorId(AnnouncementCursorCodec.AnnouncementCursor cursor) {
        if (cursor == null) {
            return null;
        }
        return cursor.id();
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
}
