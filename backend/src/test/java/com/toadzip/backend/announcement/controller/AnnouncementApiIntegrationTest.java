package com.toadzip.backend.announcement.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.ReceptionMethod;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.RentalType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AnnouncementApiIntegrationTest.FixedClockConfig.class)
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
class AnnouncementApiIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnnouncementController announcementController;

    @Test
    void 실제_PostgreSQL_조회는_최신_리비전과_공급행을_HTTP로_반환한다() throws Exception {
        Announcement original = persist(createAnnouncement(
                "api-original",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 1),
                null,
                null
        ));
        Announcement correction = persist(createAnnouncement(
                "api-correction",
                original,
                "api-original",
                AnnouncementPublicationType.CORRECTION,
                LocalDate.of(2026, 8, 2),
                new BigDecimal("1.2500"),
                "접수 일정 정정"
        ));
        HousingComplex housingComplex = persist(createHousingComplex());
        HousingType housingType = persist(createHousingType(housingComplex));
        SupplyRow matchedRow = persist(createSupplyRow(
                correction,
                housingComplex,
                housingType,
                "matched-row",
                0,
                5,
                null
        ));
        SupplyRow unmatchedRow = persist(createSupplyRow(
                correction,
                null,
                null,
                "unmatched-row",
                1,
                null,
                "단지 매칭 실패"
        ));
        persist(SupplyTarget.create(
                matchedRow,
                "청년",
                "1순위",
                5,
                2,
                new BigDecimal("12000000"),
                new BigDecimal("180000"),
                null,
                null,
                0
        ));
        persist(SupplyTarget.create(
                unmatchedRow,
                "신혼부부",
                null,
                null,
                null,
                null,
                null,
                null,
                "혼인 기간 7년 이내",
                0
        ));
        persist(AnnouncementSchedule.create(
                correction,
                ScheduleType.APPLICATION,
                "인터넷 접수",
                LocalDateTime.of(2026, 8, 10, 9, 0),
                LocalDateTime.of(2026, 8, 12, 18, 0),
                0
        ));
        persist(AnnouncementAttachment.create(
                correction,
                "정정공고문.pdf",
                AttachmentType.CORRECTION,
                "https://example.com/api-correction.pdf",
                0
        ));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/announcements"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(correction.getId()))
                .andExpect(jsonPath("$.data.items[0].publicationType").value("CORRECTION"))
                .andExpect(jsonPath("$.data.items[0].applicationStatus").value("APPLYING"))
                .andExpect(jsonPath("$.data.items[0].publishedAt").value("2026-08-02"))
                .andExpect(jsonPath("$.data.items[0].regionNames[0]").value("서울특별시 중구"))
                .andExpect(jsonPath("$.data.items[0].supplyComplexCount").value(1))
                .andExpect(jsonPath("$.data.items[0].supplyHouseholdCount").value(5))
                .andExpect(jsonPath("$.data.items[0].actualCompetitionRate").isNumber())
                .andExpect(jsonPath("$.data.items[0].predictedCompetitionRate").hasJsonPath())
                .andExpect(jsonPath("$.data.items[0].predictedCompetitionRate").value(nullValue()))
                .andExpect(jsonPath("$.data.nextCursor").hasJsonPath())
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(content().string(not(containsString("api-original 공고"))))
                .andExpect(jsonPath("$..sourceAnnouncementIdentifier").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..previousAnnouncement").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..hibernateLazyInitializer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..handler").doesNotHaveJsonPath());

        mockMvc.perform(get("/api/v1/announcements/{announcementId}", correction.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.announcementId").value(correction.getId()))
                .andExpect(jsonPath("$.data.previousAnnouncementId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.correctionOrCancellationReason").value("접수 일정 정정"))
                .andExpect(jsonPath("$.data.receptionPlaces.length()").value(1))
                .andExpect(jsonPath("$.data.schedules[0].type").value("APPLICATION"))
                .andExpect(jsonPath("$.data.attachments[0].fileType").value("CORRECTION"))
                .andExpect(jsonPath("$.data.supplyRows.length()").value(2))
                .andExpect(jsonPath("$.data.supplyRows[0].supplyRowId").value(matchedRow.getId()))
                .andExpect(jsonPath("$.data.supplyRows[0].complex.complexId").value(housingComplex.getId()))
                .andExpect(jsonPath("$.data.supplyRows[0].housingType.housingTypeId").value(housingType.getId()))
                .andExpect(jsonPath("$.data.supplyRows[0].occupancyExpectedYearMonth").value("2027-03"))
                .andExpect(jsonPath("$.data.supplyRows[0].targets[0].deposit").value(12_000_000L))
                .andExpect(jsonPath("$.data.supplyRows[1].supplyRowId").value(unmatchedRow.getId()))
                .andExpect(jsonPath("$.data.supplyRows[1].complex").hasJsonPath())
                .andExpect(jsonPath("$.data.supplyRows[1].complex").value(nullValue()))
                .andExpect(jsonPath("$.data.supplyRows[1].housingType").hasJsonPath())
                .andExpect(jsonPath("$.data.supplyRows[1].housingType").value(nullValue()))
                .andExpect(jsonPath("$.data.supplyRows[1].sourceComplexName").value("원문 단지 unmatched-row"))
                .andExpect(jsonPath("$.data.supplyRows[1].targets[0].applicationCondition")
                        .value("혼인 기간 7년 이내"))
                .andExpect(jsonPath("$.data.competition.actualRate").isNumber())
                .andExpect(jsonPath("$.data.competition.predictedRate").hasJsonPath())
                .andExpect(jsonPath("$.data.competition.predictedRate").value(nullValue()))
                .andExpect(jsonPath("$..announcement").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..supplyRow").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..hibernateLazyInitializer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$..handler").doesNotHaveJsonPath());

        mockMvc.perform(get("/api/v1/announcements/{announcementId}", original.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.announcementId").value(original.getId()))
                .andExpect(jsonPath("$.data.previousAnnouncementId").doesNotHaveJsonPath());

        assertNotNull(announcementController);
    }

    @Test
    void 취소공고가_최신인_체인은_목록에서_제외한다() throws Exception {
        Announcement original = persist(createAnnouncement(
                "api-cancelled-original",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 1),
                null,
                null
        ));
        Announcement correction = persist(createAnnouncement(
                "api-cancelled-correction",
                original,
                "api-cancelled-original",
                AnnouncementPublicationType.CORRECTION,
                LocalDate.of(2026, 8, 2),
                null,
                "접수 일정 정정"
        ));
        persist(createAnnouncement(
                "api-cancellation",
                correction,
                "api-cancelled-correction",
                AnnouncementPublicationType.CANCELLATION,
                LocalDate.of(2026, 8, 3),
                null,
                "사업 취소"
        ));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/announcements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void 취소공고는_ID로_상세조회하면_취소사유와_취소상태를_반환한다() throws Exception {
        Announcement original = persist(createAnnouncement(
                "api-detail-cancelled-original",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                LocalDate.of(2026, 8, 1),
                null,
                null
        ));
        Announcement cancellation = persist(createAnnouncement(
                "api-detail-cancellation",
                original,
                "api-detail-cancelled-original",
                AnnouncementPublicationType.CANCELLATION,
                LocalDate.of(2026, 8, 2),
                null,
                "사업 취소"
        ));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/announcements/{announcementId}", cancellation.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.announcementId").value(cancellation.getId()))
                .andExpect(jsonPath("$.data.publicationType").value("CANCELLATION"))
                .andExpect(jsonPath("$.data.correctionOrCancellationReason").value("사업 취소"))
                .andExpect(jsonPath("$.data.applicationStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.dDay").value(nullValue()))
                .andExpect(jsonPath("$.data.previousAnnouncementId").doesNotHaveJsonPath());
    }

    private Announcement createAnnouncement(
            String sourceIdentifier,
            Announcement previousAnnouncement,
            String previousSourceIdentifier,
            AnnouncementPublicationType publicationType,
            LocalDate postedDate,
            BigDecimal actualCompetitionRate,
            String correctionReason
    ) {
        return Announcement.create(
                sourceIdentifier,
                previousSourceIdentifier,
                previousAnnouncement,
                sourceIdentifier + " 공고",
                publicationType,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                postedDate,
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 20),
                "https://example.com/announcements/" + sourceIdentifier,
                correctionReason,
                3L,
                actualCompetitionRate,
                null,
                ReceptionPlace.create(
                        "LH 청약센터",
                        ReceptionMethod.ONLINE,
                        null,
                        "1600-1004",
                        "https://apply.lh.or.kr"
                )
        );
    }

    private HousingComplex createHousingComplex() {
        return HousingComplex.create(
                "서울 행복주택",
                "api-complex",
                "행복주택",
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
                "개별난방",
                "아파트",
                "계단식",
                true,
                80,
                "https://example.com/complex.png",
                null
        );
    }

    private HousingType createHousingType(HousingComplex housingComplex) {
        return HousingType.create(
                housingComplex,
                "36A",
                new BigDecimal("36.12"),
                new BigDecimal("48.00"),
                20,
                "https://example.com/floor-plan.png",
                false,
                null
        );
    }

    private SupplyRow createSupplyRow(
            Announcement announcement,
            HousingComplex housingComplex,
            HousingType housingType,
            String sourceIdentifier,
            int displayOrder,
            Integer totalSupplyHouseholdCount,
            String matchingFailureReason
    ) {
        return SupplyRow.create(
                announcement,
                housingComplex,
                housingType,
                sourceIdentifier,
                displayOrder,
                "원문 단지 " + sourceIdentifier,
                "원문 주택형 " + sourceIdentifier,
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                matchingFailureReason,
                totalSupplyHouseholdCount
        );
    }

    private <T> T persist(T entity) {
        entityManager.persist(entity);
        return entity;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedAnnouncementApiClock() {
            return Clock.fixed(Instant.parse("2026-08-09T15:00:00Z"), SEOUL);
        }
    }
}
