package com.toadzip.backend.announcement.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.repository.AdminAnnouncementImportRepository;
import com.toadzip.backend.announcement.repository.AnnouncementAttachmentRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.AnnouncementScheduleRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.announcement.repository.SupplyTargetRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAnnouncementImportIntegrationTest {

    private static final String ENDPOINT = "/api/admin/announcement-imports";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAnnouncementImportRepository importRepository;

    @Autowired
    private AnnouncementRepository announcementRepository;

    @Autowired
    private SupplyRowRepository supplyRowRepository;

    @Autowired
    private SupplyTargetRepository supplyTargetRepository;

    @Autowired
    private AnnouncementScheduleRepository scheduleRepository;

    @Autowired
    private AnnouncementAttachmentRepository attachmentRepository;

    @Autowired
    private HousingComplexRepository housingComplexRepository;

    @BeforeEach
    void cleanDatabase() {
        importRepository.deleteAll();
        supplyTargetRepository.deleteAll();
        attachmentRepository.deleteAll();
        scheduleRepository.deleteAll();
        supplyRowRepository.deleteAll();
        announcementRepository.deleteAll();
        housingComplexRepository.deleteAll();
    }

    @Test
    void 검증은_후보를_반환하지만_데이터를_저장하지_않는다() throws Exception {
        HousingComplex complex = housingComplexRepository.save(createHousingComplex());

        mockMvc.perform(post(ENDPOINT + "/validate")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registerable").value(true))
                .andExpect(jsonPath("$.data.supplyRows[0].status").value("AUTO_SELECTED"))
                .andExpect(jsonPath("$.data.supplyRows[0].suggestedHousingComplexId").value(complex.getId()));

        assertEquals(0, announcementRepository.count());
        assertEquals(0, importRepository.count());
    }

    @Test
    void 검토한_JSON은_공고와_모든_하위_데이터를_원자적으로_등록한다() throws Exception {
        HousingComplex complex = housingComplexRepository.save(createHousingComplex());

        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson(complex.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.importId").isNumber())
                .andExpect(jsonPath("$.data.announcementId").isNumber())
                .andExpect(jsonPath("$.data.supplyRowCount").value(1))
                .andExpect(jsonPath("$.data.scheduleCount").value(1))
                .andExpect(jsonPath("$.data.attachmentCount").value(1))
                .andExpect(jsonPath("$.data.supplyTargetCount").value(1));

        assertEquals(1, announcementRepository.count());
        assertEquals(1, supplyRowRepository.count());
        assertEquals(1, supplyTargetRepository.count());
        assertEquals(1, scheduleRepository.count());
        assertEquals(1, attachmentRepository.count());
        assertEquals(1, importRepository.count());
    }

    @Test
    void 같은_JSON을_다시_등록하면_409로_차단한다() throws Exception {
        HousingComplex complex = housingComplexRepository.save(createHousingComplex());
        String request = registrationJson(complex.getId());

        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated());

        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_IMPORT_DUPLICATED"));

        assertEquals(1, announcementRepository.count());
    }

    @Test
    void 미확정값이_있으면_등록을_차단한다() throws Exception {
        HousingComplex complex = housingComplexRepository.save(createHousingComplex());
        String request = registrationJson(complex.getId()).replace(
                "\"unresolvedFields\": []",
                "\"unresolvedFields\": [{\"path\":\"$.announcement.name\",\"reason\":\"확인 불가\"}]"
        );

        mockMvc.perform(post(ENDPOINT)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANNOUNCEMENT_IMPORT_INVALID"));

        assertEquals(0, announcementRepository.count());
    }

    private HousingComplex createHousingComplex() {
        return HousingComplex.create(
                "두꺼비 행복주택",
                "ADMIN-IMPORT-TEST-COMPLEX",
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

    private String registrationJson(long housingComplexId) {
        return """
                {
                  "importData": %s,
                  "complexSelections": [{"supplyRowIndex": 0, "housingComplexId": %d}]
                }
                """.formatted(importJson(), housingComplexId);
    }

    private String importJson() {
        return """
                {
                  "schemaVersion": "admin-announcement-import/v1",
                  "source": {
                    "originalUrl": "https://example.com/announcements/import-1",
                    "sourceDocumentId": "IMPORT-2026-1",
                    "extractedAt": "2026-09-18T10:30:00+09:00"
                  },
                  "announcement": {
                    "name": "2026년 행복주택 입주자 모집",
                    "rentalType": "HAPPY_HOUSING",
                    "recruitmentType": "NEW",
                    "agencyCode": "LH",
                    "postedDate": "2026-09-01",
                    "applicationStartDate": "2026-09-10",
                    "applicationEndDate": "2026-09-12",
                    "winnerAnnouncementDate": "2026-10-01"
                  },
                  "receptionPlace": {
                    "name": "LH 청약플러스",
                    "method": "ONLINE",
                    "address": null,
                    "contact": "1600-1004",
                    "url": "https://apply.lh.or.kr"
                  },
                  "supplyRows": [{
                    "complexReference": {
                      "sourceComplexName": "두꺼비 행복주택",
                      "pnu": "1114010100100010000"
                    },
                    "sourceHousingTypeName": "36A",
                    "expectedMoveInMonth": "2027-03",
                    "supplyCategory": "NEW_SUPPLY",
                    "totalSupplyHouseholdCount": 20,
                    "targets": [{
                      "target": "청년",
                      "supplyRank": "1순위",
                      "supplyHouseholdCount": 10,
                      "reserveCount": 5,
                      "rentalDeposit": 10000000,
                      "monthlyRent": 200000,
                      "convertedDeposit": null,
                      "applicationCondition": "공고문 참조"
                    }]
                  }],
                  "schedules": [{
                    "scheduleType": "APPLICATION",
                    "name": "인터넷 접수",
                    "startAt": "2026-09-10T10:00:00",
                    "endAt": "2026-09-12T17:00:00"
                  }],
                  "attachments": [{
                    "fileName": "공고문.pdf",
                    "fileType": "ANNOUNCEMENT",
                    "fileUrl": "https://example.com/files/announcement.pdf"
                  }],
                  "unresolvedFields": []
                }
                """;
    }
}
