package com.toadzip.backend.housing.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.dto.AdminChangeResponse;
import com.toadzip.backend.admin.dto.AdminDataSummary;
import com.toadzip.backend.admin.dto.AdminDocumentEvidence;
import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.dto.AdminSearch;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.ComplexVerificationStatus;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.HousingTypeAreas;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexCreateRequest.AddressRequest;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexUpdateRequest;
import com.toadzip.backend.housing.dto.request.AdminHousingTypeCreateRequest;
import com.toadzip.backend.housing.dto.request.AdminHousingTypeUpdateRequest;
import com.toadzip.backend.housing.dto.response.AdminHousingComplexDetail;
import com.toadzip.backend.housing.exception.AdminHousingComplexNotFoundException;
import com.toadzip.backend.housing.exception.InvalidRegionCodeException;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingComplexReviewRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.mapping.repository.AnnouncementSupplyMatchDecisionRepository;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.util.HashMap;
import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional(readOnly = true)
public class AdminHousingComplexManagementService {
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final SupplyRowRepository rows;
    private final AdminDataChangeRepository changes;
    private final RegionCodeResolver regions;
    private final ObjectMapper json;
    private final HousingComplexReviewRepository reviews;
    private final HousingTypeImpactService typeImpacts;
    private final AnnouncementSupplyMatchDecisionRepository matchDecisions;

    public AdminHousingComplexManagementService(HousingComplexRepository complexes, HousingTypeRepository types,
            SupplyRowRepository rows, AdminDataChangeRepository changes, RegionCodeResolver regions,
            ObjectMapper json, HousingComplexReviewRepository reviews, HousingTypeImpactService typeImpacts,
            AnnouncementSupplyMatchDecisionRepository matchDecisions) {
        this.complexes = complexes;
        this.types = types;
        this.rows = rows;
        this.changes = changes;
        this.regions = regions;
        this.json = json;
        this.reviews = reviews;
        this.typeImpacts = typeImpacts;
        this.matchDecisions = matchDecisions;
    }

    public AdminPage<AdminDataSummary> search(AdminSearch search) {
        return search(search, null);
    }

    public AdminPage<AdminDataSummary> search(AdminSearch search, ComplexVerificationStatus verification) {
        String verificationFilter = "";
        if (verification != null) {
            verificationFilter = verification.name();
        }
        var page = complexes.searchAdmin(search.pattern(), search.identifier(), search.providerCode(), search.providerLegacy(),
                search.rentalCode(), search.rentalLegacy(), search.regionCode(), search.deleted(), search.review(),
                verificationFilter, PageRequest.of(search.page(), search.pageSize()));
        var statuses = new HashMap<Long, HousingComplexReviewRepository.ReviewStatus>();
        if (!page.isEmpty()) {
            reviews.currentStatuses(page.stream().map(HousingComplex::getId).toList())
                    .forEach(status -> statuses.put(status.getId(), status));
        }
        return new AdminPage<>(page.stream().map(complex -> summary(complex, statuses.get(complex.getId()))).toList(),
                search.page(), page.hasNext(),
                page.getTotalElements(), page.getTotalPages());
    }

    public AdminHousingComplexDetail detail(long id) {
        var complex = find(id);
        var housingTypes = types.findAllIncludingTrashByHousingComplexId(id).stream()
                .map(this::housingTypeData).toList();
        var announcements = rows.findAllByHousingComplexId(id).stream().map(row -> row.getAnnouncement())
                .distinct().map(announcement -> new AdminDataSummary(announcement.getId(), announcement.getName(),
                        announcement.getPostedDate().toString(), announcement.getProvider().name(),
                        enumName(announcement.getSupplyType()), announcement.isAdminDeleted(),
                        announcement.isAdminModified(), announcement.isSourceReviewRequired(),
                        announcement.getAdminUpdatedAt(), null, new AdminDataSummary.AnnouncementSummary(
                                announcement.getSourceAnnouncementIdentifier(), announcement.getOriginalUrl(),
                                enumName(announcement.getRecruitmentType()), announcement.getPostedDate(),
                                announcement.getApplicationStartDate(), announcement.getApplicationEndDate(),
                                announcement.getWinnerAnnouncementDate(), announcement.getStatus().name(),
                                announcement.origin().name(), announcement.getSupplyOwner().name(), announcement.getReleaseStatus().name()))).toList();
        return new AdminHousingComplexDetail(summary(complex), complex.getSourceComplexIdentifier(),
                data(complex), housingTypes, announcements);
    }

    @Transactional
    public AdminHousingComplexDetail update(long id, AdminHousingComplexUpdateRequest request, String actor) {
        var complex = complexes.findByIdForUpdate(id).orElseThrow(AdminHousingComplexNotFoundException::new);
        checkVersion(complex, request.version());
        var address = request.address();
        if (regions.resolve(address.provinceCode(), address.cityCountyDistrictCode()).isEmpty()) {
            throw new InvalidRegionCodeException();
        }
        String before = json.writeValueAsString(data(complex));
        var incoming = HousingComplex.create(request.name(), complex.getSourceComplexIdentifier(),
                request.rentalType().name(), Address.create(address.roadAddress(), address.pnu(),
                        address.legalDongCode(), address.provinceCode(), address.cityCountyDistrictCode(),
                        address.latitude(), address.longitude()), request.totalHouseholdCount(),
                request.agencyCode().name(), request.completionDate(), request.heatingType(), request.buildingType(),
                request.corridorType(), request.hasElevator(), request.totalParkingCount(),
                request.overviewImageUrl(), request.moveOutCountLastYear());
        complex.reviseByAdmin(incoming);
        complexes.flush();
        changes.save(new AdminDataChange("COMPLEX", id, "UPDATE", actor, before,
                auditedData(data(complex), request.documentEvidence())));
        return detail(id);
    }

    @Transactional
    public void trash(long id, long version, boolean restore, String actor) {
        var complex = complexes.findByIdForUpdate(id).orElseThrow(AdminHousingComplexNotFoundException::new);
        checkVersion(complex, version);
        if (!restore && rows.existsByHousingComplexIdAndAnnouncementAdminDeletedFalse(id)) {
            throw new AdminDataConflictException("연결된 공고가 있습니다. 공고 연결을 변경하거나 공고를 먼저 삭제해 주세요.");
        }
        String before = json.writeValueAsString(summary(complex));
        if (restore) { complex.restore(); }
        if (!restore) { complex.moveToTrash(); }
        complexes.flush();
        String action = "DELETE";
        if (restore) { action = "RESTORE"; }
        changes.save(new AdminDataChange("COMPLEX", id, action, actor, before,
                json.writeValueAsString(summary(complex))));
    }

    @Transactional
    public AdminHousingComplexDetail updateHousingType(
            long id, long typeId, AdminHousingTypeUpdateRequest request, String actor) {
        var complex = complexes.findByIdForUpdate(id).orElseThrow(AdminHousingComplexNotFoundException::new);
        checkVersion(complex, request.version());
        if (complex.isAdminDeleted()) {
            throw new AdminDataConflictException("휴지통에서 복구한 뒤 주택형을 수정해 주세요.");
        }
        var type = types.findById(typeId)
                .orElseThrow(() -> new AdminDataConflictException("주택형이 없습니다. 새로 조회해 주세요."));
        if (type.getHousingComplex().getId() != id) {
            throw new AdminDataConflictException("이 단지에 속한 주택형만 수정할 수 있습니다.");
        }
        if (type.isAdminDeleted()) {
            throw new AdminDataConflictException("휴지통에서 복구한 뒤 주택형을 수정해 주세요.");
        }
        boolean areaChanged = type.getExclusiveArea() == null || type.getExclusiveArea().compareTo(request.exclusiveArea()) != 0;
        // 면적을 바꾸지 않는 수정은 이미 같은 면적 주택형이 둘인 기존 단지에서도 막지 않는다.
        if (areaChanged) { requireUniqueArea(id, typeId, request.exclusiveArea()); }
        typeImpacts.check(id, typeId, request.impactToken(), areaChanged);
        String before = json.writeValueAsString(housingTypeData(type));
        type.reviseByAdmin(request.name(), request.exclusiveArea(), request.householdCount());
        complex.noteHousingTypeRevisionByAdmin();
        complexes.flush();
        changes.save(new AdminDataChange("COMPLEX", id, "UPDATE_HOUSING_TYPE", actor, before,
                auditedData(housingTypeData(type), request.documentEvidence())));
        return detail(id);
    }

    @Transactional
    public AdminHousingComplexDetail createHousingType(
            long id, AdminHousingTypeCreateRequest request, String actor) {
        var complex = complexes.findByIdForUpdate(id).orElseThrow(AdminHousingComplexNotFoundException::new);
        checkVersion(complex, request.version());
        if (complex.isAdminDeleted()) {
            throw new AdminDataConflictException("휴지통에서 복구한 뒤 주택형을 추가해 주세요.");
        }
        String name = request.name().strip();
        if (types.findAllByHousingComplex(complex).stream().anyMatch(type -> type.getName().equals(name))) {
            throw new AdminDataConflictException("이 단지에 같은 이름의 주택형이 있습니다.");
        }
        requireUniqueArea(id, null, request.exclusiveArea());
        String identifier = HousingType.ADMIN_SOURCE_PREFIX + UUID.randomUUID();
        var type = request.createdFromAnnouncement()
                ? HousingType.createFromAnnouncement(complex, identifier, name, request.exclusiveArea(),
                        request.supplyArea(), request.householdCount())
                : HousingType.createByAdmin(complex, identifier, name, request.exclusiveArea(), request.supplyArea(),
                        request.householdCount());
        types.save(type);
        complex.noteHousingTypeRevisionByAdmin();
        complexes.flush();
        changes.save(new AdminDataChange("COMPLEX", id, "CREATE_HOUSING_TYPE", actor, "{}",
                auditedData(housingTypeData(type), request.documentEvidence())));
        return detail(id);
    }

    @Transactional
    public AdminHousingComplexDetail trashHousingType(long id, long typeId, long version, boolean restore, String actor) {
        var complex = complexes.findByIdForUpdate(id).orElseThrow(AdminHousingComplexNotFoundException::new);
        checkVersion(complex, version);
        var type = types.findIncludingTrashById(typeId)
                .filter(found -> found.getHousingComplex().getId() == id)
                .orElseThrow(() -> new AdminDataConflictException("이 단지에 속한 주택형이 없습니다. 새로 조회해 주세요."));
        // 휴지통 주택형은 공개·후보 조회에서 빠지므로 공급행·매칭 판단이 가리키면 연결이 끊긴다.
        if (!restore && (rows.existsByHousingType(type) || matchDecisions.existsByHousingTypeId(typeId))) {
            throw new AdminDataConflictException("이 주택형에 연결된 공급정보가 있습니다. 공고의 공급정보에서 연결을 바꾼 뒤 삭제해 주세요.");
        }
        if (restore && type.getExclusiveArea() != null && types.findAllIncludingTrashByHousingComplexId(id).stream()
                .anyMatch(other -> !other.isAdminDeleted() && !other.getId().equals(typeId) && sameArea(other, type.getExclusiveArea()))) {
            throw new AdminDataConflictException("이 단지에 같은 전용면적 주택형이 있어 복구할 수 없습니다. 그 주택형을 수정해 주세요.");
        }
        String before = json.writeValueAsString(housingTypeData(type));
        if (restore) { type.restore(); }
        if (!restore) { type.moveToTrash(); }
        complex.noteHousingTypeRevisionByAdmin();
        complexes.flush();
        String action = "DELETE_HOUSING_TYPE";
        if (restore) { action = "RESTORE_HOUSING_TYPE"; }
        changes.save(new AdminDataChange("COMPLEX", id, action, actor, before, json.writeValueAsString(housingTypeData(type))));
        return detail(id);
    }

    /**
     * 공급행은 연결 단지에서 같은 전용면적 주택형을 찾아 맞추므로, 같은 면적 주택형은 단지에 하나만 둔다.
     * 휴지통 주택형도 복구하면 겹치므로 함께 본다. exceptTypeId는 수정 중인 주택형 자신이다.
     */
    private void requireUniqueArea(long complexId, Long exceptTypeId, BigDecimal exclusiveArea) {
        var sameArea = types.findAllIncludingTrashByHousingComplexId(complexId).stream()
                .filter(type -> !type.getId().equals(exceptTypeId) && sameArea(type, exclusiveArea)).toList();
        if (sameArea.stream().anyMatch(type -> !type.isAdminDeleted())) {
            throw new AdminDataConflictException("이 단지에 같은 전용면적 주택형이 있습니다. 그 주택형을 수정해 주세요.");
        }
        if (!sameArea.isEmpty()) {
            throw new AdminDataConflictException("휴지통에 같은 전용면적 주택형이 있습니다. 복구한 뒤 수정해 주세요.");
        }
    }

    private static boolean sameArea(HousingType type, BigDecimal exclusiveArea) {
        return HousingTypeAreas.sameArea(type.getExclusiveArea(), exclusiveArea);
    }

    private AdminHousingComplexDetail.HousingTypeItem housingTypeData(HousingType type) {
        return new AdminHousingComplexDetail.HousingTypeItem(type.getId(), type.getName(),
                type.getExclusiveArea(), type.getTotalHouseholdCount(), type.isAdminDeleted());
    }

    public List<AdminChangeResponse> history(long id, int page) {
        find(id);
        return changes.findByResourceTypeAndResourceIdOrderByIdDesc("COMPLEX", id, PageRequest.of(page, 20))
                .stream().map(AdminChangeResponse::from).toList();
    }

    private HousingComplex find(long id) {
        return complexes.findById(id).orElseThrow(AdminHousingComplexNotFoundException::new);
    }

    private void checkVersion(HousingComplex complex, long version) {
        if (complex.getVersion() != version) {
            throw new AdminDataConflictException("다른 작업에서 변경한 단지입니다. 새로 조회한 뒤 다시 수정해 주세요.");
        }
    }

    private String auditedData(Object data, AdminDocumentEvidence evidence) {
        var result = (ObjectNode) json.valueToTree(data);
        if (evidence != null) {
            result.set("documentEvidence", json.valueToTree(evidence));
        }
        return json.writeValueAsString(result);
    }

    private AdminDataSummary summary(HousingComplex complex) {
        return summary(complex, reviews.currentStatuses(List.of(complex.getId())).getFirst());
    }

    private AdminDataSummary summary(HousingComplex complex, HousingComplexReviewRepository.ReviewStatus status) {
        return new AdminDataSummary(complex.getId(), complex.getName(), complex.getAddress().getRoadAddress(),
                AgencyCode.fromStoredValue(complex.getProvider()).name(),
                RentalType.fromStoredValue(complex.getSupplyType()).name(), complex.isAdminDeleted(),
                complex.isAdminModified(), complex.isSourceReviewRequired(), complex.getAdminUpdatedAt(),
                new AdminDataSummary.ComplexSummary(complex.getSourceComplexIdentifier(), complex.getCompletionDate(),
                        complex.getTotalHouseholdCount(), complex.getParkingSpaceCount(), complex.getHeatingType(),
                        complex.getHousingType(), complex.getCorridorType(), complex.getElevatorInstalled(),
                        complex.getRecentOneYearMoveOutCount(), status.getStatus(), status.getFieldCount()), null);
    }

    private AdminHousingComplexUpdateRequest data(HousingComplex complex) {
        var address = complex.getAddress();
        return new AdminHousingComplexUpdateRequest(complex.getVersion(), complex.getName(),
                RentalType.fromStoredValue(complex.getSupplyType()), AgencyCode.fromStoredValue(complex.getProvider()),
                new AddressRequest(address.getRoadAddress(), address.getPnu(), address.getLegalDongCode(),
                        address.getProvinceCode(), address.getCityCountyDistrictCode(), address.getLatitude(),
                        address.getLongitude()), complex.getTotalHouseholdCount(), complex.getCompletionDate(),
                complex.getHeatingType(), complex.getHousingType(), complex.getCorridorType(),
                complex.getElevatorInstalled(), complex.getParkingSpaceCount(), complex.getImageUrl(),
                complex.getRecentOneYearMoveOutCount());
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
