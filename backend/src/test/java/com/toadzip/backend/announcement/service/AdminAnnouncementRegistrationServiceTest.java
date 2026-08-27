package com.toadzip.backend.announcement.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest.ReceptionPlaceRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementCreateRequest.SupplyRowRequest;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementCreateResponse;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.exception.AdminHousingComplexNotFoundException;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminAnnouncementRegistrationServiceTest {

    private final AnnouncementRepository announcementRepository = mock(AnnouncementRepository.class);

    private final SupplyRowRepository supplyRowRepository = mock(SupplyRowRepository.class);

    private final HousingComplexRepository housingComplexRepository = mock(HousingComplexRepository.class);

    private final AdminAnnouncementSourceIdentifierGenerator identifierGenerator = mock(
            AdminAnnouncementSourceIdentifierGenerator.class
    );

    private final AdminAnnouncementRegistrationService service = new AdminAnnouncementRegistrationService(
            announcementRepository,
            supplyRowRepository,
            housingComplexRepository,
            identifierGenerator
    );

    @BeforeEach
    void setUpIdentifiers() {
        when(identifierGenerator.generateAnnouncementIdentifier()).thenReturn(
                "ADMIN_ENTRY-ANNOUNCEMENT-123e4567-e89b-12d3-a456-426614174000"
        );
        when(identifierGenerator.generateSupplyRowIdentifier()).thenReturn(
                "ADMIN_ENTRY-SUPPLY-ROW-123e4567-e89b-12d3-a456-426614174001"
        );
    }

    @Test
    void 원공고와_단지에_연결된_단일_공급행을_저장한다() {
        HousingComplex housingComplex = mock(HousingComplex.class);
        Announcement savedAnnouncement = mock(Announcement.class);
        SupplyRow savedSupplyRow = mock(SupplyRow.class);
        when(housingComplex.getId()).thenReturn(11L);
        when(housingComplexRepository.findById(11L)).thenReturn(Optional.of(housingComplex));
        when(announcementRepository.save(any())).thenReturn(savedAnnouncement);
        when(savedAnnouncement.getId()).thenReturn(21L);
        when(savedAnnouncement.getName()).thenReturn("2026년 행복주택 입주자 모집");
        when(supplyRowRepository.save(any())).thenReturn(savedSupplyRow);
        when(savedSupplyRow.getId()).thenReturn(31L);

        AdminAnnouncementCreateResponse response = service.register(validRequest());

        ArgumentCaptor<Announcement> announcementCaptor = ArgumentCaptor.forClass(Announcement.class);
        ArgumentCaptor<SupplyRow> supplyRowCaptor = ArgumentCaptor.forClass(SupplyRow.class);
        verify(announcementRepository).save(announcementCaptor.capture());
        verify(supplyRowRepository).save(supplyRowCaptor.capture());
        Announcement announcement = announcementCaptor.getValue();
        SupplyRow supplyRow = supplyRowCaptor.getValue();
        assertAll(
                () -> assertEquals(21L, response.announcementId()),
                () -> assertEquals(31L, response.supplyRowId()),
                () -> assertEquals(11L, response.housingComplexId()),
                () -> assertEquals(
                        "ADMIN_ENTRY-ANNOUNCEMENT-123e4567-e89b-12d3-a456-426614174000",
                        announcement.getSourceAnnouncementIdentifier()
                ),
                () -> assertEquals(AnnouncementPublicationType.ORIGINAL, announcement.getStatus()),
                () -> assertNull(announcement.getPreviousSourceAnnouncementIdentifier()),
                () -> assertNull(announcement.getPreviousAnnouncement()),
                () -> assertNull(announcement.getCorrectionCancellationReason()),
                () -> assertEquals(0L, announcement.getViewCount()),
                () -> assertNull(announcement.getActualCompetitionRate()),
                () -> assertNull(announcement.getPredictedCompetitionRate()),
                () -> assertEquals(
                        "ADMIN_ENTRY-SUPPLY-ROW-123e4567-e89b-12d3-a456-426614174001",
                        supplyRow.getSourceSupplyRowIdentifier()
                ),
                () -> assertEquals(savedAnnouncement, supplyRow.getAnnouncement()),
                () -> assertEquals(housingComplex, supplyRow.getHousingComplex()),
                () -> assertNull(supplyRow.getHousingType()),
                () -> assertEquals(0, supplyRow.getDisplayOrder()),
                () -> assertNull(supplyRow.getMatchingFailureReason()),
                () -> assertEquals("원문 두꺼비 행복주택", supplyRow.getSourceComplexName()),
                () -> assertEquals("36A", supplyRow.getSourceHousingTypeName()),
                () -> assertEquals("1114010100100010000", supplyRow.getSupplyPnu()),
                () -> assertEquals(YearMonth.of(2027, 3), supplyRow.getExpectedMoveInMonth()),
                () -> assertEquals(SupplyCategory.NEW_SUPPLY, supplyRow.getSupplyCategory()),
                () -> assertEquals(20, supplyRow.getTotalSupplyHouseholdCount())
        );
    }

    @Test
    void 없는_단지면_아무것도_저장하지_않는다() {
        when(housingComplexRepository.findById(11L)).thenReturn(Optional.empty());

        assertThrows(AdminHousingComplexNotFoundException.class, () -> service.register(validRequest()));

        verify(announcementRepository, never()).save(any());
        verify(supplyRowRepository, never()).save(any());
    }

    @Test
    void 접수_종료일이_빠르면_조회와_저장을_시작하지_않는다() {
        AdminAnnouncementCreateRequest invalid = request(
                LocalDate.of(2026, 8, 14),
                LocalDate.of(2026, 8, 10)
        );

        assertThrows(InvalidAnnouncementRequestException.class, () -> service.register(invalid));

        verify(housingComplexRepository, never()).findById(any());
        verify(announcementRepository, never()).save(any());
        verify(supplyRowRepository, never()).save(any());
    }

    private AdminAnnouncementCreateRequest validRequest() {
        return request(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 14));
    }

    private AdminAnnouncementCreateRequest request(LocalDate startDate, LocalDate endDate) {
        ReceptionPlaceRequest receptionPlace = new ReceptionPlaceRequest(
                "LH 청약센터",
                ReceptionMethod.ONLINE,
                "서울특별시 중구 세종대로 110",
                "1600-1004",
                "https://apply.lh.or.kr"
        );
        SupplyRowRequest supplyRow = new SupplyRowRequest(
                "원문 두꺼비 행복주택",
                "36A",
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                20
        );
        return new AdminAnnouncementCreateRequest(
                11L,
                "2026년 행복주택 입주자 모집",
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 1),
                startDate,
                endDate,
                LocalDate.of(2026, 9, 1),
                "https://example.com/announcements/1",
                receptionPlace,
                supplyRow
        );
    }
}
