package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.dto.AdminChangeResponse;
import com.toadzip.backend.admin.dto.AdminDataSummary;
import com.toadzip.backend.admin.dto.AdminDocumentEvidence;
import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.dto.AdminSearch;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementUpdateRequest.ReceptionPlaceUpdateRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementUpdateRequest;
import com.toadzip.backend.announcement.dto.request.VerifiedApplicationSchedulesRequest;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementDetail;
import com.toadzip.backend.announcement.dto.response.AnnouncementAttachmentResponse;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementApplicationScheduleRepository;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementComplexRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementSearchRepository;
import com.toadzip.backend.announcement.repository.SupplySheetViewQueryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional(readOnly = true)
public class AdminAnnouncementManagementService {
    private final AnnouncementSearchRepository adminQueries;
    private final Clock clock;
    private final AnnouncementRepository announcements;
    private final AnnouncementComplexRepository announcementComplexes;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;
    private final AnnouncementApplicationScheduleRepository schedules;
    private final AnnouncementAttachmentRepository attachments;
    private final SupplySheetViewQueryRepository sheets;

    public AdminAnnouncementManagementService(AnnouncementRepository announcements,
            AnnouncementComplexRepository announcementComplexes,
            AdminDataChangeRepository changes, ObjectMapper json, AnnouncementApplicationScheduleRepository schedules,
            AnnouncementAttachmentRepository attachments, AnnouncementSearchRepository adminQueries, Clock clock,
            SupplySheetViewQueryRepository sheets) {
        this.announcements = announcements;
        this.announcementComplexes = announcementComplexes;
        this.adminQueries = adminQueries;
        this.clock = clock;
        this.changes = changes;
        this.json = json;
        this.schedules = schedules;
        this.attachments = attachments;
        this.sheets = sheets;
    }

    public AdminPage<AdminDataSummary> search(AdminSearch search, long complexId) {
        return search(search, complexId, "", "");
    }

    public AdminPage<AdminDataSummary> search(AdminSearch search, long complexId, String applicationStatus,
            String attention) {
        if (!List.of("", "BEFORE_APPLICATION", "APPLYING", "CLOSED", "CONDITIONAL", "CANCELLED", "UNKNOWN")
                .contains(applicationStatus)
                || !List.of("", "ANY", "SCHEDULE_MISSING", "SUPPLY_MISSING", "UNCONFIRMED").contains(attention)) {
            throw new IllegalArgumentException("조회 조건이 올바르지 않습니다.");
        }
        LocalDate today = clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        var page = adminQueries.searchAdmin(search, complexId, applicationStatus, attention, today);
        var statuses = adminQueries.adminStatuses(page.stream().map(Announcement::getId).toList(), today).stream()
                .collect(Collectors.toMap(AnnouncementSearchRepository.AdminStatus::id, value -> value));
        return new AdminPage<>(page.stream().map(value -> summary(value, statuses.get(value.getId()))).toList(),
                search.page(), page.hasNext(), page.getTotalElements(), page.getTotalPages());
    }

    public AdminAnnouncementDetail detail(long id) {
        var announcement = find(id);
        return new AdminAnnouncementDetail(summary(announcement), announcement.getSourceAnnouncementIdentifier(),
                data(announcement), announcement.isApplicationScheduleReviewed(), sheets.read(id),
                schedules.findAllByAnnouncementIdIn(List.of(id)).stream().map(schedule ->
                        new VerifiedApplicationSchedulesRequest.Schedule(
                                scheduleComplexId(schedule), schedule.getSupplyRank(), schedule.getState(),
                                schedule.getCondition(), schedule.getStartDate(), schedule.getEndDate(),
                                schedule.getStartTime(), schedule.getEndTime(), schedule.getSourceUrl(),
                                schedule.getSourcePage())).toList(),
                attachments.findAllByAnnouncementIdIn(List.of(id)).stream().map(attachment ->
                        new AnnouncementAttachmentResponse(attachment.getId(), attachment.getFileName(),
                                attachment.getFileType(), attachment.getFileUrl())).toList());
    }

    @Transactional
    public AdminAnnouncementDetail update(long id, AdminAnnouncementUpdateRequest request, String actor) {
        var announcement = find(id);
        checkVersion(announcement, request.version());
        String before = json.writeValueAsString(data(announcement));
        var incoming = Announcement.create(announcement.getSourceAnnouncementIdentifier(), null, null,
                request.name(), AnnouncementPublicationType.ORIGINAL, request.rentalType(), request.recruitmentType(),
                request.agencyCode(), request.postedDate(), request.applicationStartDate(), request.applicationEndDate(),
                request.winnerAnnouncementDate(), request.originalUrl(), null, 0, request.places());
        announcement.reviseByAdmin(incoming);
        announcements.flush();
        changes.save(new AdminDataChange("ANNOUNCEMENT", id, "UPDATE", actor, before,
                auditedData(data(announcement), request.documentEvidence())));
        return detail(id);
    }

    @Transactional
    public void trash(long id, long version, boolean restore, String actor) {
        var announcement = find(id);
        checkVersion(announcement, version);
        if (!restore && announcements.existsByPreviousAnnouncementAndAdminDeletedFalse(announcement)) {
            throw new AdminDataConflictException("이 공고를 참조하는 정정·취소 공고가 있습니다. 후속 공고를 확인해 주세요.");
        }
        if (restore && announcement.getPreviousAnnouncement() != null
                && announcement.getPreviousAnnouncement().isAdminDeleted()) {
            throw new AdminDataConflictException("이전 공고를 먼저 복구해 주세요.");
        }
        if (restore && announcementComplexes.existsByAnnouncementIdAndHousingComplexAdminDeletedTrue(id)) {
            throw new AdminDataConflictException("연결된 단지를 먼저 복구해 주세요.");
        }
        String before = json.writeValueAsString(summary(announcement));
        String action = "DELETE";
        if (restore) { announcement.restore(); action = "RESTORE"; }
        if (!restore) { announcement.moveToTrash(); }
        announcements.flush();
        changes.save(new AdminDataChange("ANNOUNCEMENT", id, action, actor, before,
                json.writeValueAsString(summary(announcement))));
    }

    public List<AdminChangeResponse> history(long id, int page) {
        find(id);
        return changes.findByResourceTypeAndResourceIdOrderByIdDesc("ANNOUNCEMENT", id, PageRequest.of(page, 20))
                .stream().map(AdminChangeResponse::from).toList();
    }

    private Long scheduleComplexId(AnnouncementApplicationSchedule schedule) {
        if (schedule.getHousingComplex() == null) { return null; }
        return schedule.getHousingComplex().getId();
    }

    private String auditedData(Object data, AdminDocumentEvidence evidence) {
        var result = (ObjectNode) json.valueToTree(data);
        if (evidence != null) {
            result.set("documentEvidence", json.valueToTree(evidence));
        }
        return json.writeValueAsString(result);
    }

    private Announcement find(long id) {
        return announcements.findById(id).orElseThrow(AnnouncementNotFoundException::new);
    }

    private void checkVersion(Announcement announcement, long version) {
        if (announcement.getVersion() != version) {
            throw new AdminDataConflictException("다른 작업에서 변경한 공고입니다. 새로 조회한 뒤 다시 수정해 주세요.");
        }
    }

    private AdminDataSummary summary(Announcement announcement) {
        return summary(announcement, null);
    }

    private AdminDataSummary summary(Announcement announcement, AnnouncementSearchRepository.AdminStatus status) {
        LocalDate start = announcement.getApplicationStartDate();
        LocalDate end = announcement.getApplicationEndDate();
        String applicationStatus = null;
        List<String> reasons = null;
        if (status != null) {
            start = status.startDate();
            end = status.endDate();
            applicationStatus = status.applicationStatus();
            reasons = status.attentionReasons();
        }
        return new AdminDataSummary(announcement.getId(), announcement.getName(),
                announcement.getPostedDate().toString(), announcement.getProvider().name(),
                enumName(announcement.getSupplyType()), announcement.isAdminDeleted(), announcement.isAdminModified(),
                announcement.isSourceReviewRequired(), announcement.getAdminUpdatedAt(), null,
                new AdminDataSummary.AnnouncementSummary(announcement.getSourceAnnouncementIdentifier(),
                        announcement.getOriginalUrl(), enumName(announcement.getRecruitmentType()),
                        announcement.getPostedDate(), start, end, announcement.getWinnerAnnouncementDate(),
                        announcement.getStatus().name(), announcement.origin().name(),
                        announcement.isSupplyConfirmed(), announcement.getReleaseStatus().name(),
                        applicationStatus, reasons));
    }

    private AdminAnnouncementUpdateRequest data(Announcement announcement) {
        var reception = announcement.getReceptionPlaces().stream().map(ReceptionPlaceUpdateRequest::of).toList();
        return new AdminAnnouncementUpdateRequest(announcement.getVersion(), announcement.getName(),
                announcement.getSupplyType(), announcement.getRecruitmentType(), announcement.getProvider(),
                announcement.getPostedDate(), announcement.getApplicationStartDate(), announcement.getApplicationEndDate(),
                announcement.getWinnerAnnouncementDate(), announcement.getOriginalUrl(), reception);
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
