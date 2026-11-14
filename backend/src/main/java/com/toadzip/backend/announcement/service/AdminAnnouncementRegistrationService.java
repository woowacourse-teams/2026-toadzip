package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.SourceSupplySheet;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest.ReceptionPlaceRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest.SupplyRowRequest;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementCreateResponse;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplySheetStore;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.exception.AdminHousingComplexNotFoundException;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.notification.repository.NotificationOutboxRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAnnouncementRegistrationService {

    private final NotificationOutboxRepository notificationOutbox;

    private final AnnouncementRepository announcementRepository;

    private final SupplyRowRepository supplyRowRepository;

    private final HousingComplexRepository housingComplexRepository;

    private final AdminAnnouncementSourceIdentifierGenerator identifierGenerator;

    private final SupplySheetStore supplySheets;

    public AdminAnnouncementRegistrationService(
            AnnouncementRepository announcementRepository,
            SupplyRowRepository supplyRowRepository,
            HousingComplexRepository housingComplexRepository,
            AdminAnnouncementSourceIdentifierGenerator identifierGenerator,
            NotificationOutboxRepository notificationOutbox,
            SupplySheetStore supplySheets
    ) {
        this.announcementRepository = announcementRepository;
        this.supplyRowRepository = supplyRowRepository;
        this.housingComplexRepository = housingComplexRepository;
        this.identifierGenerator = identifierGenerator;
        this.notificationOutbox = notificationOutbox;
        this.supplySheets = supplySheets;
    }

    @Transactional
    public AdminAnnouncementCreateResponse register(AdminAnnouncementCreateRequest request) {
        validateApplicationPeriod(request);
        HousingComplex housingComplex = findHousingComplex(request.housingComplexId());
        Announcement created = createAnnouncement(request);
        // 공급표를 이 등록이 쓰므로 처음부터 공급표 정본으로 만든다. 저장 뒤에 표시하면 새 공고의 버전이 바로 올라간다.
        created.takeSupplySheetFromDocument();
        Announcement announcement = announcementRepository.save(created);
        SupplyRow createdSupplyRow = createSupplyRow(request.supplyRow(), announcement, housingComplex);
        SupplyRow supplyRow = supplyRowRepository.save(createdSupplyRow);
        // 대상 구분 없이 모집수만 받으므로 공급표는 '전체' 칸 하나가 그 모집수를 갖는다.
        supplySheets.replace(announcement, SourceSupplySheet.draft(List.of(new SourceSupplySheet.Row(supplyRow,
                request.supplyRow().totalSupplyHouseholdCount(), List.of()))));
        announcementRepository.flush();
        notificationOutbox.appendNewAnnouncement(announcement);
        return new AdminAnnouncementCreateResponse(
                announcement.getId(),
                supplyRow.getId(),
                housingComplex.getId(),
                announcement.getName()
        );
    }

    private HousingComplex findHousingComplex(long housingComplexId) {
        return housingComplexRepository.findByIdForUpdate(housingComplexId)
                .filter(value -> !value.isAdminDeleted())
                .orElseThrow(AdminHousingComplexNotFoundException::new);
    }

    private Announcement createAnnouncement(AdminAnnouncementCreateRequest request) {
        return Announcement.create(
                identifierGenerator.generateAnnouncementIdentifier(),
                null,
                null,
                request.name(),
                AnnouncementPublicationType.ORIGINAL,
                request.rentalType(),
                request.recruitmentType(),
                request.agencyCode(),
                request.postedDate(),
                request.applicationStartDate(),
                request.applicationEndDate(),
                request.winnerAnnouncementDate(),
                request.originalUrl(),
                null,
                0L,
                request.receptionPlaces().stream().map(ReceptionPlaceRequest::toPlace).toList()
        );
    }

    private SupplyRow createSupplyRow(
            SupplyRowRequest request,
            Announcement announcement,
            HousingComplex housingComplex
    ) {
        return SupplyRow.create(
                announcement,
                housingComplex,
                null,
                identifierGenerator.generateSupplyRowIdentifier(),
                0,
                request.sourceComplexName(),
                request.sourceHousingTypeName(),
                request.supplyPnu(),
                request.expectedMoveInMonth(),
                request.supplyCategory(),
                null,
                request.totalSupplyHouseholdCount()
        );
    }

    private void validateApplicationPeriod(AdminAnnouncementCreateRequest request) {
        if (request.applicationEndDate().isBefore(request.applicationStartDate())) {
            throw new InvalidAnnouncementRequestException();
        }
    }
}
