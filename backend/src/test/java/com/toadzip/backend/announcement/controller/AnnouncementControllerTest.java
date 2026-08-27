package com.toadzip.backend.announcement.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.domain.SupplyType;
import com.toadzip.backend.announcement.dto.request.AnnouncementSearchRequest;
import com.toadzip.backend.announcement.dto.response.AgencyResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementAttachmentResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementDetailResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListItemResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementListResponse;
import com.toadzip.backend.announcement.dto.response.AnnouncementScheduleResponse;
import com.toadzip.backend.announcement.dto.response.CompetitionResponse;
import com.toadzip.backend.announcement.dto.response.HousingTypeResponse;
import com.toadzip.backend.announcement.dto.response.ReceptionPlaceResponse;
import com.toadzip.backend.announcement.dto.response.SupplyComplexResponse;
import com.toadzip.backend.announcement.dto.response.SupplyRowResponse;
import com.toadzip.backend.announcement.dto.response.SupplyTargetResponse;
import com.toadzip.backend.announcement.exception.AnnouncementNotFoundException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementCursorException;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.exception.InvalidRegionCodeException;
import com.toadzip.backend.announcement.service.AnnouncementQueryService;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(AnnouncementController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AnnouncementExceptionAdvice.class)
class AnnouncementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnnouncementQueryService announcementQueryService;

    @Test
    void 목록은_data_봉투와_공개_DTO_필드만_JSON으로_반환한다() throws Exception {
        when(announcementQueryService.getAnnouncements(noFilters(), null, 20)).thenReturn(listResponse());

        mockMvc.perform(get("/api/v1/announcements"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(expectedListJson(), JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.data.items[0].actualCompetitionRate").isNumber())
                .andExpect(jsonPath("$..sourceAnnouncementIdentifier").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..previousAnnouncement").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..announcement").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..supplyRow").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..hibernateLazyInitializer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..handler").doesNotHaveJsonPath());

        verify(announcementQueryService).getAnnouncements(noFilters(), null, 20);
    }

    @Test
    void 생략하거나_빈_size는_기본값_20으로_서비스에_전달한다() throws Exception {
        when(announcementQueryService.getAnnouncements(noFilters(), null, 20)).thenReturn(listResponse());

        mockMvc.perform(get("/api/v1/announcements"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/announcements").param("size", ""))
                .andExpect(status().isOk());

        verify(announcementQueryService, times(2)).getAnnouncements(noFilters(), null, 20);
    }

    @Test
    void 목록_검색_쿼리와_반복_enum_값을_서비스로_전달한다() throws Exception {
        when(announcementQueryService.getAnnouncements(
                org.mockito.ArgumentMatchers.any(AnnouncementSearchRequest.class),
                org.mockito.ArgumentMatchers.eq("cursor-value"),
                org.mockito.ArgumentMatchers.eq(15)
        )).thenReturn(listResponse());

        mockMvc.perform(get("/api/v1/announcements")
                        .param("keyword", "행복주택")
                        .param("regionCode", "11140")
                        .param("rentalTypes", "HAPPY_HOUSING", "NATIONAL_RENTAL")
                        .param("applicationStatuses", "BEFORE_APPLICATION", "APPLYING")
                        .param("publicationTypes", "ORIGINAL", "CORRECTION")
                        .param("agencyCodes", "LH", "SH")
                        .param("recruitmentTypes", "NEW", "WAITLIST")
                        .param("applicationFrom", "2026-08-01")
                        .param("applicationTo", "2026-08-31")
                        .param("cursor", "cursor-value")
                        .param("size", "15"))
                .andExpect(status().isOk());

        ArgumentCaptor<AnnouncementSearchRequest> requestCaptor = ArgumentCaptor.forClass(
                AnnouncementSearchRequest.class
        );
        verify(announcementQueryService).getAnnouncements(
                requestCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("cursor-value"),
                org.mockito.ArgumentMatchers.eq(15)
        );
        AnnouncementSearchRequest request = requestCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("행복주택", request.keyword());
        org.junit.jupiter.api.Assertions.assertEquals("11140", request.regionCode());
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of(RentalType.HAPPY_HOUSING, RentalType.NATIONAL_RENTAL), request.rentalTypes()
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of(ApplicationStatus.BEFORE_APPLICATION, ApplicationStatus.APPLYING), request.applicationStatuses()
        );
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of(AnnouncementPublicationType.ORIGINAL, AnnouncementPublicationType.CORRECTION), request.publicationTypes()
        );
        org.junit.jupiter.api.Assertions.assertEquals(List.of(AgencyCode.LH, AgencyCode.SH), request.agencyCodes());
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of(RecruitmentType.NEW, RecruitmentType.WAITLIST), request.recruitmentTypes()
        );
        org.junit.jupiter.api.Assertions.assertEquals(LocalDate.of(2026, 8, 1), request.applicationFrom());
        org.junit.jupiter.api.Assertions.assertEquals(LocalDate.of(2026, 8, 31), request.applicationTo());
    }

    @Test
    void 상세는_data_봉투와_명세의_날짜_연월_null_필드를_JSON으로_반환한다() throws Exception {
        when(announcementQueryService.getAnnouncement(42L)).thenReturn(detailResponse());

        mockMvc.perform(get("/api/v1/announcements/{announcementId}", 42L))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(expectedDetailJson(), JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.data.competition.actualRate").isNumber())
                .andExpect(jsonPath("$.data.receptionPlaces.length()").value(1))
                .andExpect(jsonPath("$.data.previousAnnouncementId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..sourceAnnouncementIdentifier").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..previousAnnouncement").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..announcement").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..supplyRow").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..hibernateLazyInitializer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..handler").doesNotHaveJsonPath());

        verify(announcementQueryService).getAnnouncement(42L);
    }

    @Test
    void size_허용범위_밖의_값은_고정된_INVALID_REQUEST로_반환한다() throws Exception {
        when(announcementQueryService.getAnnouncements(noFilters(), null, 0))
                .thenThrow(new InvalidAnnouncementRequestException());
        when(announcementQueryService.getAnnouncements(noFilters(), null, 51))
                .thenThrow(new InvalidAnnouncementRequestException());

        assertError(
                mockMvc.perform(get("/api/v1/announcements").param("size", "0")),
                400,
                "INVALID_REQUEST",
                "요청 값을 확인해 주세요."
        );
        assertError(
                mockMvc.perform(get("/api/v1/announcements").param("size", "51")),
                400,
                "INVALID_REQUEST",
                "요청 값을 확인해 주세요."
        );
    }

    @Test
    void 잘못되거나_빈_cursor는_고정된_INVALID_CURSOR로_반환한다() throws Exception {
        when(announcementQueryService.getAnnouncements(noFilters(), "bad-cursor", 20))
                .thenThrow(new InvalidAnnouncementCursorException());
        when(announcementQueryService.getAnnouncements(noFilters(), "", 20))
                .thenThrow(new InvalidAnnouncementCursorException());

        assertError(
                mockMvc.perform(get("/api/v1/announcements").param("cursor", "bad-cursor")),
                400,
                "INVALID_CURSOR",
                "커서 값을 확인해 주세요."
        );
        assertError(
                mockMvc.perform(get("/api/v1/announcements").param("cursor", "")),
                400,
                "INVALID_CURSOR",
                "커서 값을 확인해 주세요."
        );
    }

    @Test
    void 없는_0_음수_ID는_고정된_ANNOUNCEMENT_NOT_FOUND로_반환한다() throws Exception {
        when(announcementQueryService.getAnnouncement(999L)).thenThrow(new AnnouncementNotFoundException());
        when(announcementQueryService.getAnnouncement(0L)).thenThrow(new AnnouncementNotFoundException());
        when(announcementQueryService.getAnnouncement(-1L)).thenThrow(new AnnouncementNotFoundException());

        assertAnnouncementNotFound(999L);
        assertAnnouncementNotFound(0L);
        assertAnnouncementNotFound(-1L);
    }

    @Test
    void 숫자로_변환할_수_없는_size와_ID는_필드가_포함된_VALIDATION_FAILED로_반환한다() throws Exception {
        assertValidationError(
                mockMvc.perform(get("/api/v1/announcements").param("size", "abc")),
                "size"
        );
        assertValidationError(
                mockMvc.perform(get("/api/v1/announcements").param("size", "2147483648")),
                "size"
        );
        assertValidationError(
                mockMvc.perform(get("/api/v1/announcements/not-a-number")),
                "announcementId"
        );
    }

    @Test
    void 잘못된_enum과_날짜는_필드가_포함된_VALIDATION_FAILED로_반환한다() throws Exception {
        assertValidationErrorWithField(
                mockMvc.perform(get("/api/v1/announcements").param("agencyCodes", "UNKNOWN")),
                "agencyCodes"
        );
        assertValidationErrorWithField(
                mockMvc.perform(get("/api/v1/announcements").param("applicationFrom", "2026/08/01")),
                "applicationFrom"
        );
    }

    @Test
    void 유효값과_빈_enum_반복값은_필드가_포함된_VALIDATION_FAILED로_반환한다() throws Exception {
        assertValidationErrorWithFieldAndNoLeaks(
                mockMvc.perform(get("/api/v1/announcements").param("agencyCodes", "LH", "")),
                "agencyCodes"
        );
    }

    @Test
    void 잘못된_지역코드는_고정된_INVALID_REGION_CODE로_반환한다() throws Exception {
        AnnouncementSearchRequest request = new AnnouncementSearchRequest(
                null, "99999", null, null, null, null, null, null, null
        );
        when(announcementQueryService.getAnnouncements(request, null, 20))
                .thenThrow(new InvalidRegionCodeException());

        assertError(
                mockMvc.perform(get("/api/v1/announcements").param("regionCode", "99999")),
                400,
                "INVALID_REGION_CODE",
                "지역 코드를 확인해 주세요."
        );
    }

    private AnnouncementSearchRequest noFilters() {
        return new AnnouncementSearchRequest(null, null, null, null, null, null, null, null, null);
    }

    private void assertAnnouncementNotFound(long announcementId) throws Exception {
        assertError(
                mockMvc.perform(get("/api/v1/announcements/{announcementId}", announcementId)),
                404,
                "ANNOUNCEMENT_NOT_FOUND",
                "모집 공고를 찾을 수 없습니다."
        );
    }

    private void assertError(
            ResultActions resultActions,
            int statusCode,
            String code,
            String message
    ) throws Exception {
        String body = resultActions
                .andExpect(status().is(statusCode))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors").doesNotHaveJsonPath())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("Exception"));
        assertFalse(body.contains("SQL"));
        assertFalse(body.contains("stack"));
    }

    private void assertValidationError(ResultActions resultActions, String field) throws Exception {
        String body = resultActions
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].reason").value("형식이 올바르지 않습니다."))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("Exception"));
        assertFalse(body.contains("SQL"));
        assertFalse(body.contains("stack"));
    }

    private void assertValidationErrorWithField(ResultActions resultActions, String field) throws Exception {
        resultActions
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    private void assertValidationErrorWithFieldAndNoLeaks(ResultActions resultActions, String field) throws Exception {
        String body = resultActions
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value(org.hamcrest.Matchers.startsWith(field)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("Exception"));
        assertFalse(body.contains("SQL"));
        assertFalse(body.contains("stack"));
    }

    private AnnouncementListResponse listResponse() {
        AnnouncementListItemResponse item = new AnnouncementListItemResponse(
                42L,
                AnnouncementPublicationType.ORIGINAL,
                ApplicationStatus.APPLYING,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                "2026년 행복주택 입주자 모집",
                List.of("서울특별시 중구"),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 10),
                0,
                7L,
                1,
                null,
                new AgencyResponse(AgencyCode.LH, "한국토지주택공사"),
                new BigDecimal("2.5000"),
                null,
                null
        );
        return new AnnouncementListResponse(List.of(item), null, false);
    }

    private AnnouncementDetailResponse detailResponse() {
        SupplyTargetResponse target = new SupplyTargetResponse(
                401L,
                "청년",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        SupplyRowResponse matchedRow = new SupplyRowResponse(
                301L,
                "원문 단지",
                "36A",
                new SupplyComplexResponse(
                        101L,
                        "서울 행복주택",
                        "서울특별시 중구 세종대로 110",
                        100,
                        null
                ),
                new HousingTypeResponse(
                        201L,
                        "36A",
                        new BigDecimal("36.12"),
                        null,
                        "https://example.com/floor-plan.png",
                        null
                ),
                YearMonth.of(2027, 3),
                SupplyType.NEW,
                5,
                List.of(target)
        );
        SupplyRowResponse unmatchedRow = new SupplyRowResponse(
                302L,
                "미매칭 원문 단지",
                "미매칭 44A",
                null,
                null,
                null,
                SupplyType.RESUPPLY,
                null,
                List.of()
        );
        return new AnnouncementDetailResponse(
                42L,
                AnnouncementPublicationType.ORIGINAL,
                null,
                ApplicationStatus.APPLYING,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                "2026년 행복주택 입주자 모집",
                List.of("서울특별시 중구"),
                new AgencyResponse(AgencyCode.LH, "한국토지주택공사"),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 10),
                0,
                LocalDate.of(2026, 8, 20),
                7L,
                List.of("청년"),
                1,
                5,
                "https://example.com/announcement",
                List.of(new ReceptionPlaceResponse(
                        "LH 청약센터",
                        ReceptionMethod.ONLINE,
                        null,
                        "1600-1004",
                        "https://apply.lh.or.kr"
                )),
                List.of(new AnnouncementScheduleResponse(
                        501L,
                        ScheduleType.APPLICATION,
                        "인터넷 접수",
                        LocalDateTime.of(2026, 8, 10, 9, 30, 15),
                        LocalDateTime.of(2026, 8, 10, 18, 0, 15)
                )),
                List.of(new AnnouncementAttachmentResponse(
                        601L,
                        "공고문.pdf",
                        AttachmentType.ANNOUNCEMENT,
                        "https://example.com/announcement.pdf"
                )),
                List.of(matchedRow, unmatchedRow),
                new CompetitionResponse(new BigDecimal("2.5000"), null)
        );
    }

    private String expectedListJson() {
        return """
                {
                  "data": {
                    "items": [{
                      "announcementId": 42,
                      "publicationType": "ORIGINAL",
                      "applicationStatus": "APPLYING",
                      "rentalType": "HAPPY_HOUSING",
                      "recruitmentType": "NEW",
                      "title": "2026년 행복주택 입주자 모집",
                      "regionNames": ["서울특별시 중구"],
                      "publishedAt": "2026-08-01",
                      "applicationStartAt": "2026-08-10",
                      "applicationEndAt": "2026-08-10",
                      "dDay": 0,
                      "viewCount": 7,
                      "supplyComplexCount": 1,
                      "supplyHouseholdCount": null,
                      "agency": {"code": "LH", "name": "한국토지주택공사"},
                      "actualCompetitionRate": 2.5000,
                      "predictedCompetitionRate": null,
                      "thumbnailImageUrl": null
                    }],
                    "nextCursor": null,
                    "hasNext": false
                  }
                }
                """;
    }

    private String expectedDetailJson() {
        return """
                {
                  "data": {
                    "announcementId": 42,
                    "publicationType": "ORIGINAL",
                    "correctionOrCancellationReason": null,
                    "applicationStatus": "APPLYING",
                    "rentalType": "HAPPY_HOUSING",
                    "recruitmentType": "NEW",
                    "title": "2026년 행복주택 입주자 모집",
                    "regionNames": ["서울특별시 중구"],
                    "agency": {"code": "LH", "name": "한국토지주택공사"},
                    "publishedAt": "2026-08-01",
                    "applicationStartAt": "2026-08-10",
                    "applicationEndAt": "2026-08-10",
                    "dDay": 0,
                    "winnerAnnouncementAt": "2026-08-20",
                    "viewCount": 7,
                    "targets": ["청년"],
                    "supplyComplexCount": 1,
                    "supplyHouseholdCount": 5,
                    "documentLinkUrl": "https://example.com/announcement",
                    "receptionPlaces": [{
                      "name": "LH 청약센터",
                      "method": "ONLINE",
                      "address": null,
                      "phoneNumber": "1600-1004",
                      "url": "https://apply.lh.or.kr"
                    }],
                    "schedules": [{
                      "scheduleId": 501,
                      "type": "APPLICATION",
                      "name": "인터넷 접수",
                      "startAt": "2026-08-10T09:30:15",
                      "endAt": "2026-08-10T18:00:15"
                    }],
                    "attachments": [{
                      "attachmentId": 601,
                      "fileName": "공고문.pdf",
                      "fileType": "ANNOUNCEMENT",
                      "fileUrl": "https://example.com/announcement.pdf"
                    }],
                    "supplyRows": [{
                      "supplyRowId": 301,
                      "sourceComplexName": "원문 단지",
                      "sourceHousingTypeName": "36A",
                      "complex": {
                        "complexId": 101,
                        "name": "서울 행복주택",
                        "address": "서울특별시 중구 세종대로 110",
                        "totalHouseholdCount": 100,
                        "overviewImageUrl": null
                      },
                      "housingType": {
                        "housingTypeId": 201,
                        "name": "36A",
                        "exclusiveArea": 36.12,
                        "supplyArea": null,
                        "floorPlanImageUrl": "https://example.com/floor-plan.png",
                        "floorPlan3dImageUrl": null
                      },
                      "occupancyExpectedYearMonth": "2027-03",
                      "supplyType": "NEW",
                      "totalSupplyHouseholdCount": 5,
                      "targets": [{
                        "supplyTargetId": 401,
                        "target": "청년",
                        "priority": null,
                        "supplyHouseholdCount": null,
                        "waitlistCount": null,
                        "deposit": null,
                        "monthlyRent": null,
                        "convertibleDeposit": null,
                        "applicationCondition": null
                      }]
                    }, {
                      "supplyRowId": 302,
                      "sourceComplexName": "미매칭 원문 단지",
                      "sourceHousingTypeName": "미매칭 44A",
                      "complex": null,
                      "housingType": null,
                      "occupancyExpectedYearMonth": null,
                      "supplyType": "RESUPPLY",
                      "totalSupplyHouseholdCount": null,
                      "targets": []
                    }],
                    "competition": {"actualRate": 2.5000, "predictedRate": null}
                  }
                }
                """;
    }
}
