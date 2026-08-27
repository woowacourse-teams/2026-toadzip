package com.toadzip.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.domain.AdminAccount;
import com.toadzip.backend.admin.repository.AdminAccountRepository;
import com.toadzip.backend.admin.repository.AdminAuthenticationAuditLogRepository;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminDataRegistrationFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminAccountRepository adminAccountRepository;

    @Autowired
    private AdminAuthenticationAuditLogRepository auditLogRepository;

    @Autowired
    private HousingComplexRepository housingComplexRepository;

    @Autowired
    private AnnouncementRepository announcementRepository;

    @Autowired
    private SupplyRowRepository supplyRowRepository;

    @BeforeEach
    void setUp() {
        supplyRowRepository.deleteAll();
        announcementRepository.deleteAll();
        housingComplexRepository.deleteAll();
        auditLogRepository.deleteAll();
        adminAccountRepository.deleteAll();
        entityManager.flush();
        adminAccountRepository.save(
                AdminAccount.create(
                        "admin",
                        passwordEncoder.encode("correct-password"),
                        LocalDateTime.of(2026, 8, 28, 9, 0)
                )
        );
    }

    @Test
    void 실제_로그인과_CSRF로_단지_공고_공급행을_연속_저장한다() throws Exception {
        CsrfFixture loginCsrf = issueCsrfToken();
        MvcResult loginResult = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginIdentifier\":\"admin\",\"password\":\"correct-password\"}")
                        .cookie(loginCsrf.cookie())
                        .header(loginCsrf.headerName(), loginCsrf.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginIdentifier").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        CsrfFixture housingCsrf = issueCsrfToken(session, loginCsrf.cookie());
        MvcResult housingResult = mockMvc.perform(authenticatedPost(
                        "/api/admin/housing-complexes",
                        session,
                        housingCsrf,
                        housingRequest()
                ))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.housingComplexId").isNumber())
                .andReturn();
        long housingComplexId = responseData(housingResult).get("housingComplexId").asLong();

        CsrfFixture announcementCsrf = issueCsrfToken(session, housingCsrf.cookie());
        MvcResult announcementResult = mockMvc.perform(authenticatedPost(
                        "/api/admin/announcements",
                        session,
                        announcementCsrf,
                        announcementRequest(housingComplexId)
                ))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.housingComplexId").value(housingComplexId))
                .andReturn();
        JsonNode announcementData = responseData(announcementResult);
        long announcementId = announcementData.get("announcementId").asLong();
        long supplyRowId = announcementData.get("supplyRowId").asLong();

        entityManager.flush();
        entityManager.clear();

        HousingComplex housingComplex = housingComplexRepository.findById(housingComplexId).orElseThrow();
        Announcement announcement = announcementRepository.findById(announcementId).orElseThrow();
        SupplyRow supplyRow = supplyRowRepository.findById(supplyRowId).orElseThrow();
        assertAll(
                () -> assertEquals(1, housingComplexRepository.count()),
                () -> assertEquals(1, announcementRepository.count()),
                () -> assertEquals(1, supplyRowRepository.count()),
                () -> assertIdentifier(
                        housingComplex.getSourceComplexIdentifier(),
                        "ADMIN_ENTRY-HOUSING-COMPLEX-"
                ),
                () -> assertIdentifier(
                        announcement.getSourceAnnouncementIdentifier(),
                        "ADMIN_ENTRY-ANNOUNCEMENT-"
                ),
                () -> assertIdentifier(
                        supplyRow.getSourceSupplyRowIdentifier(),
                        "ADMIN_ENTRY-SUPPLY-ROW-"
                ),
                () -> assertEquals(AnnouncementPublicationType.ORIGINAL, announcement.getStatus()),
                () -> assertNull(announcement.getPreviousAnnouncement()),
                () -> assertNull(announcement.getPreviousSourceAnnouncementIdentifier()),
                () -> assertNull(announcement.getCorrectionCancellationReason()),
                () -> assertEquals(LocalDate.of(2026, 8, 1), announcement.getPostedDate()),
                () -> assertEquals("https://example.com/announcements/42", announcement.getOriginalUrl()),
                () -> assertEquals(0L, announcement.getViewCount()),
                () -> assertNull(announcement.getActualCompetitionRate()),
                () -> assertNull(announcement.getPredictedCompetitionRate()),
                () -> assertEquals(announcementId, supplyRow.getAnnouncement().getId()),
                () -> assertEquals(housingComplexId, supplyRow.getHousingComplex().getId()),
                () -> assertNull(supplyRow.getHousingType()),
                () -> assertEquals(0, supplyRow.getDisplayOrder()),
                () -> assertEquals("원문 두꺼비 행복주택", supplyRow.getSourceComplexName()),
                () -> assertEquals("36A", supplyRow.getSourceHousingTypeName()),
                () -> assertEquals("1114010100100010000", supplyRow.getSupplyPnu()),
                () -> assertEquals(YearMonth.of(2027, 3), supplyRow.getExpectedMoveInMonth()),
                () -> assertEquals(SupplyCategory.NEW_SUPPLY, supplyRow.getSupplyCategory()),
                () -> assertNull(supplyRow.getMatchingFailureReason()),
                () -> assertEquals(20, supplyRow.getTotalSupplyHouseholdCount())
        );
    }

    private CsrfFixture issueCsrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        return csrfFixture(result, null);
    }

    private CsrfFixture issueCsrfToken(MockHttpSession session, Cookie currentCookie) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/auth/csrf")
                        .session(session)
                        .cookie(currentCookie))
                .andExpect(status().isOk())
                .andReturn();
        return csrfFixture(result, currentCookie);
    }

    private CsrfFixture csrfFixture(MvcResult result, Cookie currentCookie) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        Cookie responseCookie = result.getResponse().getCookie("XSRF-TOKEN");
        Cookie cookie = responseCookie == null ? currentCookie : responseCookie;
        if (cookie == null) {
            throw new IllegalStateException("CSRF 쿠키가 발급되지 않았다.");
        }
        return new CsrfFixture(response.get("token").asText(), response.get("headerName").asText(), cookie);
    }

    private MockHttpServletRequestBuilder authenticatedPost(
            String endpoint,
            MockHttpSession session,
            CsrfFixture csrfFixture,
            String content
    ) {
        return post(endpoint)
                .session(session)
                .cookie(csrfFixture.cookie())
                .header(csrfFixture.headerName(), csrfFixture.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(content);
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private void assertIdentifier(String identifier, String prefix) {
        assertThat(identifier).startsWith(prefix);
        String uuidText = identifier.substring(prefix.length());
        assertEquals(uuidText, UUID.fromString(uuidText).toString());
    }

    private String housingRequest() {
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
                  "totalHouseholdCount": 100,
                  "completionDate": "2020-06-30",
                  "heatingType": "INDIVIDUAL",
                  "buildingType": "APARTMENT",
                  "corridorType": "STAIR",
                  "hasElevator": true,
                  "totalParkingCount": 80,
                  "overviewImageUrl": "https://example.com/complex.png",
                  "moveOutCountLastYear": 5
                }
                """;
    }

    private String announcementRequest(long housingComplexId) {
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
                  "originalUrl": "https://example.com/announcements/42",
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

    private record CsrfFixture(String token, String headerName, Cookie cookie) {
    }
}
