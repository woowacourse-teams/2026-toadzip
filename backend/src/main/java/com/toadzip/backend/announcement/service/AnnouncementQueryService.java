package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListItemResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListResponse;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
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
    private final AnnouncementResponseMapper announcementResponseMapper;
    private final AnnouncementCursorCodec announcementCursorCodec;
    private final Clock clock;

    public AnnouncementQueryService(
            AnnouncementRepository announcementRepository,
            AnnouncementScheduleRepository announcementScheduleRepository,
            AnnouncementAttachmentRepository announcementAttachmentRepository,
            SupplyRowRepository supplyRowRepository,
            SupplyTargetRepository supplyTargetRepository,
            AnnouncementResponseMapper announcementResponseMapper,
            AnnouncementCursorCodec announcementCursorCodec,
            Clock clock
    ) {
        this.announcementRepository = announcementRepository;
        this.announcementScheduleRepository = announcementScheduleRepository;
        this.announcementAttachmentRepository = announcementAttachmentRepository;
        this.supplyRowRepository = supplyRowRepository;
        this.supplyTargetRepository = supplyTargetRepository;
        this.announcementResponseMapper = announcementResponseMapper;
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
}
