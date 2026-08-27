package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAnnouncementRegistrationIntegrationTest {

    private static final String ENDPOINT = "/api/admin/announcements";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AnnouncementRepository announcementRepository;

    @MockitoSpyBean
    private SupplyRowRepository supplyRowRepository;

    @Autowired
    private HousingComplexRepository housingComplexRepository;

    @BeforeEach
    void cleanDatabase() {
        supplyRowRepository.deleteAll();
        announcementRepository.deleteAll();
        housingComplexRepository.deleteAll();
    }

    @Test
    @Transactional
    void 관리자는_원공고와_단지에_연결된_단일_공급행을_저장한다() throws Exception {
        HousingComplex housingComplex = housingComplexRepository.save(createHousingComplex());

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(housingComplex.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.announcementId").isNumber())
                .andExpect(jsonPath("$.data.supplyRowId").isNumber())
                .andExpect(jsonPath("$.data.housingComplexId").value(housingComplex.getId()))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        long announcementId = data.get("announcementId").asLong();
        long supplyRowId = data.get("supplyRowId").asLong();
        entityManager.flush();
        entityManager.clear();

        Announcement announcement = announcementRepository.findById(announcementId).orElseThrow();
        SupplyRow supplyRow = supplyRowRepository.findById(supplyRowId).orElseThrow();
        assertAnnouncement(announcement);
        assertSupplyRow(supplyRow, announcementId, housingComplex.getId());
    }

    @Test
    void 없는_단지_ID면_404이고_아무것도_저장하지_않는다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOUSING_COMPLEX_NOT_FOUND"));

        assertEquals(0, announcementRepository.count());
        assertEquals(0, supplyRowRepository.count());
    }

    @Test
    void 공급행_저장에_실패하면_앞서_저장한_공고도_롤백한다() throws Exception {
        HousingComplex housingComplex = housingComplexRepository.save(createHousingComplex());
        doThrow(new IllegalStateException("supply row persistence failure"))
                .when(supplyRowRepository)
                .save(any(SupplyRow.class));

        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(housingComplex.getId())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));

        assertEquals(0, announcementRepository.count());
        assertEquals(0, supplyRowRepository.count());
    }

    @Test
    void CSRF_토큰이_없으면_공고를_저장할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(999L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertEquals(0, announcementRepository.count());
    }

    @Test
    void 비관리자는_공고를_저장할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(user("member").roles("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(999L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertEquals(0, announcementRepository.count());
    }

    @Test
    void 비로그인_요청은_공고를_저장할_수_없다() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(999L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertEquals(0, announcementRepository.count());
    }

    private void assertAnnouncement(Announcement announcement) {
        assertAll(
                () -> assertIdentifier(announcement.getSourceAnnouncementIdentifier(), "ADMIN_ENTRY-ANNOUNCEMENT-"),
                () -> assertEquals(AnnouncementPublicationType.ORIGINAL, announcement.getStatus()),
                () -> assertNull(announcement.getPreviousSourceAnnouncementIdentifier()),
                () -> assertNull(announcement.getPreviousAnnouncement()),
                () -> assertNull(announcement.getCorrectionCancellationReason()),
                () -> assertEquals(LocalDate.of(2026, 8, 1), announcement.getPostedDate()),
                () -> assertEquals("https://example.com/announcements/1", announcement.getOriginalUrl()),
                () -> assertEquals(0L, announcement.getViewCount()),
                () -> assertNull(announcement.getActualCompetitionRate()),
                () -> assertNull(announcement.getPredictedCompetitionRate())
        );
    }

    private void assertSupplyRow(SupplyRow supplyRow, long announcementId, long housingComplexId) {
        assertAll(
                () -> assertIdentifier(supplyRow.getSourceSupplyRowIdentifier(), "ADMIN_ENTRY-SUPPLY-ROW-"),
                () -> assertEquals(announcementId, supplyRow.getAnnouncement().getId()),
                () -> assertEquals(housingComplexId, supplyRow.getHousingComplex().getId()),
                () -> assertNull(supplyRow.getHousingType()),
                () -> assertEquals(0, supplyRow.getDisplayOrder()),
                () -> assertEquals("원문 두꺼비 행복주택", supplyRow.getSourceComplexName()),
                () -> assertEquals("36A", supplyRow.getSourceHousingTypeName()),
                () -> assertEquals("1114010100100010000", supplyRow.getSupplyPnu()),
                () -> assertEquals(SupplyCategory.NEW_SUPPLY, supplyRow.getSupplyCategory()),
                () -> assertNull(supplyRow.getMatchingFailureReason()),
                () -> assertEquals(20, supplyRow.getTotalSupplyHouseholdCount())
        );
    }

    private void assertIdentifier(String identifier, String prefix) {
        assertThat(identifier).startsWith(prefix);
        String uuidText = identifier.substring(prefix.length());
        assertEquals(uuidText, UUID.fromString(uuidText).toString());
    }

    private HousingComplex createHousingComplex() {
        return HousingComplex.create(
                "두꺼비 행복주택",
                "ADMIN_ENTRY-HOUSING-COMPLEX-123e4567-e89b-12d3-a456-426614174000",
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
                "INDIVIDUAL",
                "APARTMENT",
                "STAIR",
                true,
                80,
                null,
                0
        );
    }

    private String validRequest(long housingComplexId) {
        return """
                {
                  "housingComplexId": %d,
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
                """.formatted(housingComplexId);
    }
}
