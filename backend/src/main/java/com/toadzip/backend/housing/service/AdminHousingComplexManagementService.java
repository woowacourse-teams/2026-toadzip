package com.toadzip.backend.housing.service;

import com.toadzip.backend.admin.domain.AdminDataChange;
import com.toadzip.backend.admin.dto.AdminChangeResponse;
import com.toadzip.backend.admin.dto.AdminDataSummary;
import com.toadzip.backend.admin.dto.AdminPage;
import com.toadzip.backend.admin.dto.AdminSearch;
import com.toadzip.backend.admin.exception.AdminDataConflictException;
import com.toadzip.backend.admin.repository.AdminDataChangeRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexCreateRequest.AddressRequest;
import com.toadzip.backend.housing.dto.request.AdminHousingComplexUpdateRequest;
import com.toadzip.backend.housing.dto.response.AdminHousingComplexDetail;
import com.toadzip.backend.housing.exception.AdminHousingComplexNotFoundException;
import com.toadzip.backend.housing.exception.InvalidRegionCodeException;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class AdminHousingComplexManagementService {
    private final HousingComplexRepository complexes;
    private final HousingTypeRepository types;
    private final SupplyRowRepository rows;
    private final AdminDataChangeRepository changes;
    private final RegionCodeResolver regions;
    private final ObjectMapper json;

    public AdminHousingComplexManagementService(HousingComplexRepository complexes, HousingTypeRepository types,
            SupplyRowRepository rows, AdminDataChangeRepository changes, RegionCodeResolver regions,
            ObjectMapper json) {
        this.complexes = complexes;
        this.types = types;
        this.rows = rows;
        this.changes = changes;
        this.regions = regions;
        this.json = json;
    }

    public AdminPage<AdminDataSummary> search(AdminSearch search) {
        var page = complexes.searchAdmin(search.pattern(), search.identifier(), search.providerCode(), search.providerLegacy(),
                search.rentalCode(), search.rentalLegacy(), search.regionCode(), search.deleted(), search.review(),
                PageRequest.of(search.page(), search.pageSize()));
        return new AdminPage<>(page.stream().map(this::summary).toList(), search.page(), page.hasNext(),
                page.getTotalElements(), page.getTotalPages());
    }

    public AdminHousingComplexDetail detail(long id) {
        var complex = find(id);
        var housingTypes = types.findAllByHousingComplex(complex).stream()
                .map(type -> new AdminHousingComplexDetail.HousingTypeItem(type.getId(), type.getName(),
                        type.getExclusiveArea(), type.getTotalHouseholdCount())).toList();
        var announcements = rows.findAllByHousingComplexId(id).stream().map(row -> row.getAnnouncement())
                .distinct().map(announcement -> new AdminDataSummary(announcement.getId(), announcement.getName(),
                        announcement.getPostedDate().toString(), announcement.getProvider().name(),
                        announcement.getSupplyType().name(), announcement.isAdminDeleted(),
                        announcement.isAdminModified(), announcement.isSourceReviewRequired(),
                        announcement.getAdminUpdatedAt(), null, new AdminDataSummary.AnnouncementSummary(
                                announcement.getSourceAnnouncementIdentifier(), announcement.getOriginalUrl(),
                                announcement.getRecruitmentType().name(), announcement.getPostedDate(),
                                announcement.getApplicationStartDate(), announcement.getApplicationEndDate(),
                                announcement.getWinnerAnnouncementDate()))).toList();
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
        record(complex, "UPDATE", actor, before);
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

    private void record(HousingComplex complex, String action, String actor, String before) {
        changes.save(new AdminDataChange("COMPLEX", complex.getId(), action, actor, before,
                json.writeValueAsString(data(complex))));
    }

    private AdminDataSummary summary(HousingComplex complex) {
        return new AdminDataSummary(complex.getId(), complex.getName(), complex.getAddress().getRoadAddress(),
                AgencyCode.fromStoredValue(complex.getProvider()).name(),
                RentalType.fromStoredValue(complex.getSupplyType()).name(), complex.isAdminDeleted(),
                complex.isAdminModified(), complex.isSourceReviewRequired(), complex.getAdminUpdatedAt(),
                new AdminDataSummary.ComplexSummary(complex.getSourceComplexIdentifier(), complex.getCompletionDate(),
                        complex.getTotalHouseholdCount(), complex.getParkingSpaceCount(), complex.getHeatingType(),
                        complex.getHousingType(), complex.getCorridorType(), complex.getElevatorInstalled(),
                        complex.getRecentOneYearMoveOutCount()), null);
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
}
