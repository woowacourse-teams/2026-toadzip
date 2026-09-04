package com.toadzip.backend.announcement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.dto.response.AdminAnnouncementCreateResponse;
import com.toadzip.backend.announcement.exception.InvalidAnnouncementRequestException;
import com.toadzip.backend.announcement.service.AdminAnnouncementRegistrationService;
import com.toadzip.backend.global.exception.GlobalExceptionAdvice;
import com.toadzip.backend.housing.controller.HousingComplexExceptionAdvice;
import com.toadzip.backend.housing.exception.AdminHousingComplexNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminAnnouncementController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        AnnouncementExceptionAdvice.class,
        HousingComplexExceptionAdvice.class,
        GlobalExceptionAdvice.class
})
class AdminAnnouncementControllerTest {

    private static final String ENDPOINT = "/api/admin/announcements";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminAnnouncementRegistrationService registrationService;

    @Test
    void 관리자_공고_등록은_201과_생성_ID를_반환한다() throws Exception {
        when(registrationService.register(any())).thenReturn(new AdminAnnouncementCreateResponse(
                21L,
                31L,
                11L,
                "2026년 행복주택 입주자 모집"
        ));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.announcementId").value(21))
                .andExpect(jsonPath("$.data.supplyRowId").value(31))
                .andExpect(jsonPath("$.data.housingComplexId").value(11))
                .andExpect(jsonPath("$.data.name").value("2026년 행복주택 입주자 모집"));
    }

    @Test
    void 필수값_공백_문자열_길이_URL_숫자를_검증한다() throws Exception {
        String invalidRequest = validRequest()
                .replace("2026년 행복주택 입주자 모집", "가".repeat(256))
                .replace("LH 청약센터", " ")
                .replace("https://example.com/announcements/1", "ftp://example.com/announcements/1")
                .replace("\"totalSupplyHouseholdCount\": 20", "\"totalSupplyHouseholdCount\": -1");

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'originalUrl')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'receptionPlace.name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'supplyRow.totalSupplyHouseholdCount')]").exists());
    }

    @Test
    void enum_날짜_연월_형식이_잘못되면_INVALID_REQUEST를_반환한다() throws Exception {
        assertInvalidRequest(validRequest().replace("HAPPY_HOUSING", "행복주택"));
        assertInvalidRequest(validRequest().replace("2026-08-01", "2026/08/01"));
        assertInvalidRequest(validRequest().replace("2027-03", "03-2027"));
    }

    @Test
    void 접수_종료일이_시작일보다_빠르면_INVALID_REQUEST를_반환한다() throws Exception {
        when(registrationService.register(any())).thenThrow(new InvalidAnnouncementRequestException());

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("2026-08-14", "2026-08-09")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 없는_단지_ID는_HOUSING_COMPLEX_NOT_FOUND를_반환한다() throws Exception {
        when(registrationService.register(any())).thenThrow(new AdminHousingComplexNotFoundException());

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOUSING_COMPLEX_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("단지를 찾을 수 없습니다."));
    }

    private void assertInvalidRequest(String request) throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private String validRequest() {
        return """
                {
                  "housingComplexId": 11,
                  "name": "2026년 행복주택 입주자 모집",
                  "rentalType": "HAPPY_HOUSING",
                  "recruitmentType": "NEW",
                  "agencyCode": "LH",
                  "postedDate": "2026-08-01",
                  "applicationStartDate": "2026-08-10",
                  "applicationEndDate": "2026-08-14",
                  "winnerAnnouncementDate": "2026-09-01",
                  "originalUrl": "https://example.com/announcements/1",
                  "receptionPlace": {
                    "name": "LH 청약센터",
                    "method": "ONLINE",
                    "address": "서울특별시 중구 세종대로 110",
                    "contact": "1600-1004",
                    "url": "https://apply.lh.or.kr"
                  },
                  "supplyRow": {
                    "sourceComplexName": "원문 두꺼비 행복주택",
                    "sourceHousingTypeName": "36A",
                    "supplyPnu": "1114010100100010000",
                    "expectedMoveInMonth": "2027-03",
                    "supplyCategory": "NEW_SUPPLY",
                    "totalSupplyHouseholdCount": 20
                  }
                }
                """;
    }
}
