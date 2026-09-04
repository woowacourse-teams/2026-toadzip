package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest.ReceptionPlaceRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest.SupplyRowRequest;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementCreateResponse;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.exception.AdminHousingComplexNotFoundException;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAnnouncementRegistrationService {

    private final AnnouncementRepository announcementRepository;

    private final SupplyRowRepository supplyRowRepository;

    private final HousingComplexRepository housingComplexRepository;

    private final AdminAnnouncementSourceIdentifierGenerator identifierGenerator;

    public AdminAnnouncementRegistrationService(
            AnnouncementRepository announcementRepository,
            SupplyRowRepository supplyRowRepository,
            HousingComplexRepository housingComplexRepository,
            AdminAnnouncementSourceIdentifierGenerator identifierGenerator
    ) {
        this.announcementRepository = announcementRepository;
        this.supplyRowRepository = supplyRowRepository;
        this.housingComplexRepository = housingComplexRepository;
        this.identifierGenerator = identifierGenerator;
    }

    @Transactional
    public AdminAnnouncementCreateResponse register(AdminAnnouncementCreateRequest request) {
        validateApplicationPeriod(request);
        HousingComplex housingComplex = findHousingComplex(request.housingComplexId());
        Announcement announcement = announcementRepository.save(createAnnouncement(request));
        SupplyRow createdSupplyRow = createSupplyRow(request.supplyRow(), announcement, housingComplex);
        SupplyRow supplyRow = supplyRowRepository.save(createdSupplyRow);
        return new AdminAnnouncementCreateResponse(
                announcement.getId(),
                supplyRow.getId(),
                housingComplex.getId(),
                announcement.getName()
        );
    }

    private HousingComplex findHousingComplex(long housingComplexId) {
        return housingComplexRepository.findById(housingComplexId)
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
                createReceptionPlace(request.receptionPlace())
        );
    }

    private ReceptionPlace createReceptionPlace(ReceptionPlaceRequest request) {
        return ReceptionPlace.create(
                request.name(),
                request.method(),
                request.address(),
                request.contact(),
                request.url()
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
