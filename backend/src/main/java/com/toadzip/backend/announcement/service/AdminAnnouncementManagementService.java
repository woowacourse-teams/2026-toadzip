package com.toadzip.backend.announcement.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.dto.AdminChangeResponse;
import com.toadzip.backend.admin.dto.AdminDataSummary;
import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.dto.AdminSearch;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementUpdateRequest;
import com.toadzip.backend.announcement.dto.request.AdminSupplyRowUpdateRequest;
import com.toadzip.backend.announcement.dto.request.VerifiedApplicationSchedulesRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest.ReceptionPlaceRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest.SupplyRowRequest;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementDetail;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.repository.AnnouncementApplicationScheduleRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class AdminAnnouncementManagementService {
    private final AnnouncementRepository announcements;
    private final SupplyRowRepository rows;
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final AdminDataChangeRepository changes;
    private final ObjectMapper json;
    private final AnnouncementApplicationScheduleRepository schedules;

    public AdminAnnouncementManagementService(AnnouncementRepository announcements, SupplyRowRepository rows,
            HousingComplexRepository complexes, HousingTypeRepository types,
            AdminDataChangeRepository changes, ObjectMapper json, AnnouncementApplicationScheduleRepository schedules) {
        this.announcements = announcements;
        this.rows = rows;
        this.complexes = complexes;
        this.types = types;
        this.changes = changes;
        this.json = json;
        this.schedules = schedules;
    }

    public AdminPage<AdminDataSummary> search(AdminSearch search, long complexId) {
        var page = announcements.searchAdmin(search.pattern(), search.identifier(), search.providerCode(), search.providerLegacy(),
                search.rentalCode(), search.rentalLegacy(), search.regionCode(), search.deleted(), search.review(),
                complexId, PageRequest.of(search.page(), search.pageSize()));
        return new AdminPage<>(page.stream().map(this::summary).toList(), search.page(), page.hasNext(),
                page.getTotalElements(), page.getTotalPages());
    }

    public AdminAnnouncementDetail detail(long id) {
        var announcement = find(id);
        return new AdminAnnouncementDetail(summary(announcement), announcement.getSourceAnnouncementIdentifier(),
                data(announcement), announcement.isApplicationScheduleReviewed(),
                rows.findAllByAnnouncement(announcement).stream().map(this::rowData).toList(),
                schedules.findAllByAnnouncementIdIn(List.of(id)).stream().map(schedule ->
                        new VerifiedApplicationSchedulesRequest.Schedule(
                                scheduleComplexId(schedule), schedule.getSupplyRank(), schedule.getState(),
                                schedule.getCondition(), schedule.getStartDate(), schedule.getEndDate(),
                                schedule.getStartTime(), schedule.getEndTime(), schedule.getSourceUrl(),
                                schedule.getSourcePage())).toList());
    }

    @Transactional
    public AdminAnnouncementDetail update(long id, AdminAnnouncementUpdateRequest request, String actor) {
        var announcement = find(id);
        checkVersion(announcement, request.version());
        String before = json.writeValueAsString(data(announcement));
        ReceptionPlace reception = null;
        if (request.receptionPlace() != null) {
            var place = request.receptionPlace();
            reception = ReceptionPlace.create(place.name(), place.method(), place.address(), place.contact(), place.url());
        }
        var incoming = Announcement.create(announcement.getSourceAnnouncementIdentifier(), null, null,
                request.name(), AnnouncementPublicationType.ORIGINAL, request.rentalType(), request.recruitmentType(),
                request.agencyCode(), request.postedDate(), request.applicationStartDate(), request.applicationEndDate(),
                request.winnerAnnouncementDate(), request.originalUrl(), null, 0, reception);
        announcement.reviseByAdmin(incoming);
        announcements.flush();
        changes.save(new AdminDataChange("ANNOUNCEMENT", id, "UPDATE", actor, before,
                json.writeValueAsString(data(announcement))));
        return detail(id);
    }

    @Transactional
    public AdminAnnouncementDetail updateRow(long id, long rowId, AdminSupplyRowUpdateRequest request, String actor) {
        var announcement = find(id);
        checkVersion(announcement, request.version());
        if (announcement.isAdminDeleted()) { throw new AdminDataConflictException("공고를 복구한 뒤 수정해 주세요."); }
        var row = rows.findById(rowId).filter(value -> value.getAnnouncement().getId().equals(id))
                .orElseThrow(AnnouncementNotFoundException::new);
        String before = json.writeValueAsString(rowData(row));
        HousingComplex complex = null;
        HousingType type = null;
        if (request.housingComplexId() != null) {
            complex = complexes.findById(request.housingComplexId()).filter(value -> !value.isAdminDeleted())
                    .orElseThrow(() -> new AdminDataConflictException("연결할 단지를 찾을 수 없습니다."));
        }
        if (request.housingTypeId() != null) {
            type = types.findById(request.housingTypeId()).orElseThrow(AnnouncementNotFoundException::new);
            if (complex == null || !type.getHousingComplex().getId().equals(complex.getId())) {
                throw new AdminDataConflictException("선택한 단지에 속한 주택형을 선택해 주세요.");
            }
        }
        var values = request.supplyRow();
        row.reviseByAdmin(complex, type, values.sourceComplexName(), values.sourceHousingTypeName(),
                values.supplyPnu(), values.expectedMoveInMonth(), values.supplyCategory(),
                values.totalSupplyHouseholdCount());
        var linkedComplexIds = rows.findAllByAnnouncement(announcement).stream()
                .filter(value -> value.getHousingComplex() != null)
                .map(value -> value.getHousingComplex().getId()).collect(java.util.stream.Collectors.toSet());
        if (schedules.findAllByAnnouncementIdIn(List.of(id)).stream().anyMatch(schedule ->
                schedule.getHousingComplex() != null && !linkedComplexIds.contains(schedule.getHousingComplex().getId()))) {
            throw new AdminDataConflictException("이 단지에 지정된 접수 일정이 있습니다. 일정의 적용 단지를 먼저 변경해 주세요.");
        }
        announcement.recordAdminSupplyChange();
        announcements.flush();
        changes.save(new AdminDataChange("ANNOUNCEMENT", id, "UPDATE_SUPPLY", actor, before,
                json.writeValueAsString(rowData(row))));
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
        if (restore && rows.findAllByAnnouncement(announcement).stream().anyMatch(row ->
                row.getHousingComplex() != null && row.getHousingComplex().isAdminDeleted())) {
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

    private Announcement find(long id) {
        return announcements.findById(id).orElseThrow(AnnouncementNotFoundException::new);
    }

    private void checkVersion(Announcement announcement, long version) {
        if (announcement.getVersion() != version) {
            throw new AdminDataConflictException("다른 작업에서 변경한 공고입니다. 새로 조회한 뒤 다시 수정해 주세요.");
        }
    }

    private AdminDataSummary summary(Announcement announcement) {
        return new AdminDataSummary(announcement.getId(), announcement.getName(),
                announcement.getPostedDate().toString(), announcement.getProvider().name(),
                announcement.getSupplyType().name(), announcement.isAdminDeleted(), announcement.isAdminModified(),
                announcement.isSourceReviewRequired(), announcement.getAdminUpdatedAt());
    }

    private AdminAnnouncementUpdateRequest data(Announcement announcement) {
        ReceptionPlaceRequest reception = null;
        if (announcement.getReceptionPlace() != null) {
            var place = announcement.getReceptionPlace();
            reception = new ReceptionPlaceRequest(place.getName(), place.getMethod(), place.getAddress(),
                    place.getContact(), place.getUrl());
        }
        return new AdminAnnouncementUpdateRequest(announcement.getVersion(), announcement.getName(),
                announcement.getSupplyType(), announcement.getRecruitmentType(), announcement.getProvider(),
                announcement.getPostedDate(), announcement.getApplicationStartDate(), announcement.getApplicationEndDate(),
                announcement.getWinnerAnnouncementDate(), announcement.getOriginalUrl(), reception);
    }

    private AdminAnnouncementDetail.SupplyRowItem rowData(SupplyRow row) {
        Long complexId = null;
        String complexName = null;
        Long typeId = null;
        if (row.getHousingComplex() != null) {
            complexId = row.getHousingComplex().getId(); complexName = row.getHousingComplex().getName();
        }
        if (row.getHousingType() != null) { typeId = row.getHousingType().getId(); }
        return new AdminAnnouncementDetail.SupplyRowItem(row.getId(), complexId, complexName, typeId,
                row.isAdminModified(), new SupplyRowRequest(row.getSourceComplexName(), row.getSourceHousingTypeName(),
                        row.getSupplyPnu(), row.getExpectedMoveInMonth(), row.getSupplyCategory(),
                        row.getTotalSupplyHouseholdCount()));
    }
}
