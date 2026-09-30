package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.AnnouncementRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.ComplexReferenceRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.ReceptionPlaceRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.SourceRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.SupplyRowRequest;
import com.toadzip.backend.announcement.dto.request.AdminAnnouncementImportRequest.UnresolvedFieldRequest;
import com.toadzip.backend.announcement.dto.response.AdminAnnouncementImportValidationResponse;
import com.toadzip.backend.announcement.repository.AdminAnnouncementImportRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminAnnouncementImportValidationServiceTest {

    private HousingComplexRepository housingComplexRepository;
    private AdminAnnouncementImportRepository importRepository;
    private AnnouncementRepository announcementRepository;
    private AdminAnnouncementImportValidationService service;

    @BeforeEach
    void setUp() {
        housingComplexRepository = mock(HousingComplexRepository.class);
        importRepository = mock(AdminAnnouncementImportRepository.class);
        announcementRepository = mock(AnnouncementRepository.class);
        AdminAnnouncementImportJson importJson = mock(AdminAnnouncementImportJson.class);
        Validator validator = mock(Validator.class);
        when(importJson.serialize(any())).thenReturn("{}");
        when(importJson.hash("{}")).thenReturn("hash");
        when(importJson.isWithinSizeLimit("{}")).thenReturn(true);
        when(validator.validate(any(AdminAnnouncementImportRequest.class))).thenReturn(Set.of());
        service = new AdminAnnouncementImportValidationService(
                housingComplexRepository,
                importRepository,
                announcementRepository,
                importJson,
                validator
        );
    }

    @Test
    void 후보가_한_건이면_자동_선택하고_등록_가능하다() {
        HousingComplex complex = complex("두꺼비 행복주택", "COMPLEX-1", 1L);
        when(housingComplexRepository.findAllByPnuAndSupplyType(
                "1114010100100010000",
                "HAPPY_HOUSING"
        )).thenReturn(List.of(complex));

        AdminAnnouncementImportValidationResponse response = service.validate(validRequest(List.of()));

        assertThat(response.registerable()).isTrue();
        assertThat(response.supplyRows()).singleElement().satisfies(match -> {
            assertThat(match.status()).isEqualTo("AUTO_SELECTED");
            assertThat(match.suggestedHousingComplexId()).isEqualTo(complex.getId());
        });
    }

    @Test
    void 후보가_복수면_관리자_선택을_요구한다() {
        when(housingComplexRepository.findAllByPnuAndSupplyType(
                "1114010100100010000",
                "HAPPY_HOUSING"
        )).thenReturn(List.of(
                complex("다른 이름", "COMPLEX-2", 2L),
                complex("두꺼비 행복주택", "COMPLEX-1", 1L)
        ));

        AdminAnnouncementImportValidationResponse response = service.validate(validRequest(List.of()));

        assertThat(response.registerable()).isTrue();
        assertThat(response.supplyRows()).singleElement().satisfies(match -> {
            assertThat(match.status()).isEqualTo("SELECTION_REQUIRED");
            assertThat(match.suggestedHousingComplexId()).isNull();
            assertThat(match.candidates().getFirst().name()).isEqualTo("두꺼비 행복주택");
        });
    }

    @Test
    void 미확정값이_있으면_검토_결과는_반환하지만_등록을_차단한다() {
        when(housingComplexRepository.findAllByPnuAndSupplyType(
                "1114010100100010000",
                "HAPPY_HOUSING"
        )).thenReturn(List.of(complex("두꺼비 행복주택", "COMPLEX-1", 1L)));
        List<UnresolvedFieldRequest> unresolved = List.of(
                new UnresolvedFieldRequest("$.supplyRows[0].expectedMoveInMonth", "원문에서 확인할 수 없음")
        );

        AdminAnnouncementImportValidationResponse response = service.validate(validRequest(unresolved));

        assertThat(response.registerable()).isFalse();
        assertThat(response.unresolvedFields()).isEqualTo(unresolved);
    }

    @Test
    void 같은_원문_URL의_기존_공고가_있으면_중복으로_차단한다() {
        when(housingComplexRepository.findAllByPnuAndSupplyType(
                "1114010100100010000",
                "HAPPY_HOUSING"
        )).thenReturn(List.of(complex("두꺼비 행복주택", "COMPLEX-1", 1L)));
        when(announcementRepository.existsByOriginalUrl("https://example.com/announcement"))
                .thenReturn(true);

        AdminAnnouncementImportValidationResponse response = service.validate(validRequest(List.of()));

        assertThat(response.registerable()).isFalse();
        assertThat(response.duplicated()).isTrue();
        assertThat(response.warnings()).singleElement().satisfies(warning ->
                assertThat(warning.reason()).contains("이미 등록된")
        );
    }

    private AdminAnnouncementImportRequest validRequest(List<UnresolvedFieldRequest> unresolvedFields) {
        return new AdminAnnouncementImportRequest(
                "admin-announcement-import/v1",
                new SourceRequest(
                        "https://example.com/announcement",
                        "2026-1",
                        OffsetDateTime.parse("2026-09-18T10:30:00+09:00")
                ),
                new AnnouncementRequest(
                        "2026년 행복주택 입주자 모집",
                        RentalType.HAPPY_HOUSING,
                        RecruitmentType.NEW,
                        AgencyCode.LH,
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 10),
                        LocalDate.of(2026, 9, 12),
                        LocalDate.of(2026, 10, 1)
                ),
                new ReceptionPlaceRequest(
                        "LH 청약플러스",
                        ReceptionMethod.ONLINE,
                        null,
                        "1600-1004",
                        "https://apply.lh.or.kr"
                ),
                List.of(new SupplyRowRequest(
                        new ComplexReferenceRequest("두꺼비 행복주택", "1114010100100010000"),
                        "36A",
                        null,
                        SupplyCategory.NEW_SUPPLY,
                        20,
                        List.of()
                )),
                List.of(),
                List.of(),
                unresolvedFields
        );
    }

    private HousingComplex complex(String name, String identifier, long id) {
        HousingComplex complex = HousingComplex.create(
                name,
                identifier,
                "HAPPY_HOUSING",
                Address.create(
                        "서울특별시 중구 세종대로 110",
                        "1114010100100010000",
                        "1114010100",
                        "11",
                        "11140",
                        new BigDecimal("37.566500"),
                        new BigDecimal("126.978000")
                ),
                100,
                "LH",
                LocalDate.of(2020, 6, 30),
                null,
                null,
                null,
                null,
                0,
                null,
                null
        );
        setId(complex, id);
        return complex;
    }

    private void setId(HousingComplex complex, long id) {
        try {
            var field = HousingComplex.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(complex, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
