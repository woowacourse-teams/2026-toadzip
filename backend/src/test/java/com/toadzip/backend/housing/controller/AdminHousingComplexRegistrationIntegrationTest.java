package com.toadzip.backend.housing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminHousingComplexRegistrationIntegrationTest {

    private static final String ENDPOINT = "/api/admin/housing-complexes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private HousingComplexRepository housingComplexRepository;

    @Test
    void 관리자는_단지를_저장하고_생성된_핵심_정보를_받는다() throws Exception {
        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.housingComplexId").isNumber())
                .andExpect(jsonPath("$.data.name").value("두꺼비 행복주택"))
                .andExpect(jsonPath("$.data.roadAddress").value("서울특별시 중구 세종대로 110"))
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        long housingComplexId = response.get("data").get("housingComplexId").asLong();
        entityManager.flush();
        entityManager.clear();

        HousingComplex saved = housingComplexRepository.findById(housingComplexId).orElseThrow();
        String sourceIdentifier = saved.getSourceComplexIdentifier();
        String uuidText = sourceIdentifier.substring("ADMIN_ENTRY-HOUSING-COMPLEX-".length());
        assertAll(
                () -> assertThat(sourceIdentifier).startsWith("ADMIN_ENTRY-HOUSING-COMPLEX-"),
                () -> assertEquals(uuidText, UUID.fromString(uuidText).toString()),
                () -> assertEquals("HAPPY_HOUSING", saved.getSupplyType()),
                () -> assertEquals("LH", saved.getProvider()),
                () -> assertEquals(0, saved.getTotalHouseholdCount()),
                () -> assertEquals(0, saved.getParkingSpaceCount()),
                () -> assertEquals("37.566500", saved.getAddress().getLatitude().toPlainString()),
                () -> assertEquals("126.978000", saved.getAddress().getLongitude().toPlainString())
        );
    }

    @Test
    void 누락_공백_범위_URL_검증_실패는_입력값_오류를_반환한다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("요청값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'totalHouseholdCount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'totalParkingCount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'overviewImageUrl')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'address.latitude')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'address.longitude')]").exists());
    }

    @Test
    void 기존_영문_enum_코드가_아닌_값은_잘못된_요청으로_거부한다() throws Exception {
        String invalidEnumRequest = validRequest().replace("HAPPY_HOUSING", "행복주택");

        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidEnumRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void ISO_날짜가_아니면_잘못된_요청으로_거부한다() throws Exception {
        String invalidDateRequest = validRequest().replace("2020-06-30", "2020/06/30");

        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidDateRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void CSRF_토큰이_없으면_단지를_저장할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertEquals(0, housingComplexRepository.count());
    }

    @Test
    void 비관리자는_단지를_저장할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(user("member").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertEquals(0, housingComplexRepository.count());
    }

    @Test
    void 비로그인_요청은_단지를_저장할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertEquals(0, housingComplexRepository.count());
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
                  "name": " ",
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
                  "totalParkingCount": -1,
                  "overviewImageUrl": "ftp://example.com/complex.png",
                  "moveOutCountLastYear": 0
                }
                """;
    }
}
