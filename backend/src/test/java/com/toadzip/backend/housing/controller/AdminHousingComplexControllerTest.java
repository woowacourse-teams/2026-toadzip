package com.toadzip.backend.housing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.global.exception.GlobalExceptionAdvice;
import com.toadzip.backend.housing.dto.response.AdminHousingComplexCreateResponse;
import com.toadzip.backend.housing.service.AdminHousingComplexRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminHousingComplexController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionAdvice.class)
class AdminHousingComplexControllerTest {

    private static final String ENDPOINT = "/api/admin/housing-complexes";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminHousingComplexRegistrationService registrationService;

    @Test
    void 관리자_단지_등록은_201과_data_응답을_반환한다() throws Exception {
        when(registrationService.register(any())).thenReturn(new AdminHousingComplexCreateResponse(
                42L,
                "두꺼비 행복주택",
                "서울특별시 중구 세종대로 110"
        ));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.housingComplexId").value(42))
                .andExpect(jsonPath("$.data.name").value("두꺼비 행복주택"))
                .andExpect(jsonPath("$.data.roadAddress").value("서울특별시 중구 세종대로 110"));
    }

    @Test
    void 필수_숫자_누락과_범위_위반은_검증_오류를_반환한다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'totalHouseholdCount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'totalParkingCount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'address.latitude')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'address.longitude')]").exists());
    }

    @Test
    void 문자열_길이와_공백_HTTP_URL을_검증한다() throws Exception {
        String invalidStringRequest = validRequest()
                .replace("두꺼비 행복주택", "가".repeat(256))
                .replace("서울특별시 중구 세종대로 110", " ")
                .replace("https://example.com/complex.png", "ftp://example.com/complex.png");

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidStringRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'address.roadAddress')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'overviewImageUrl')]").exists());
    }

    @Test
    void 한글_enum_코드는_잘못된_요청으로_거부한다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("HAPPY_HOUSING", "행복주택")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void ISO_날짜가_아니면_잘못된_요청으로_거부한다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest().replace("2020-06-30", "2020/06/30")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private String validRequest() {
        return """
                {
                  "name": "두꺼비 행복주택",
                  "rentalType": "HAPPY_HOUSING",
                  "agencyCode": "LH",
                  "address": {
                    "roadAddress": "서울특별시 중구 세종대로 110",
                    "pnu": "1114010100100010000",
                    "legalDongCode": "1114010100",
                    "provinceCode": "11",
                    "cityCountyDistrictCode": "11140",
                    "latitude": 37.566500,
                    "longitude": 126.978000
                  },
                  "totalHouseholdCount": 0,
                  "completionDate": "2020-06-30",
                  "heatingType": "INDIVIDUAL",
                  "buildingType": "APARTMENT",
                  "corridorType": "STAIR",
                  "hasElevator": true,
                  "totalParkingCount": 0,
                  "overviewImageUrl": "https://example.com/complex.png",
                  "moveOutCountLastYear": 0
                }
                """;
    }

    private String invalidRequest() {
        return """
                {
                  "name": "두꺼비 행복주택",
                  "rentalType": "HAPPY_HOUSING",
                  "agencyCode": "LH",
                  "address": {
                    "roadAddress": "서울특별시 중구 세종대로 110",
                    "pnu": "1114010100100010000",
                    "legalDongCode": "1114010100",
                    "provinceCode": "11",
                    "cityCountyDistrictCode": "11140",
                    "latitude": 90.0000001,
                    "longitude": 181
                  },
                  "completionDate": "2020-06-30",
                  "heatingType": "INDIVIDUAL",
                  "buildingType": "APARTMENT",
                  "corridorType": "STAIR",
                  "hasElevator": true,
                  "totalParkingCount": -1
                }
                """;
    }
}
