package com.toadzip.backend.announcement.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    private ObjectMapper objectMapper;

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
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalCount").value(0));
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

    @Test
    void 읍면동_공고는_연결된_단지의_법정동으로_필터링한다() throws Exception {
        HousingComplex first = persist(createHousingComplex("dong-first", "11140"));
        HousingComplex second = persist(createHousingComplex("dong-second", "11140"));
        Announcement included = persist(createAnnouncement("dong-included", null, null,
                AnnouncementPublicationType.ORIGINAL, LocalDate.of(2026, 8, 1), null, null));
        Announcement excluded = persist(createAnnouncement("dong-excluded", null, null,
                AnnouncementPublicationType.ORIGINAL, LocalDate.of(2026, 8, 1), null, null));
        persistSearchSupplyRows(first, included);
        persistSearchSupplyRows(second, excluded);
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE housing_complexes SET legal_dong_code = '1114010200' WHERE id = :id")
                .setParameter("id", second.getId()).executeUpdate();
        entityManager.clear();
        mockMvc.perform(get("/api/v1/announcements").param("regionCode", "1114010100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(included.getId()));
    }

    @Test
    void 시도_전체_지역코드는_소속_시군구의_공고를_조회한다() throws Exception {
        HousingComplex seoulComplex = persist(createHousingComplex("api-province-seoul", "11140"));
        Announcement announcement = persist(createSearchAnnouncement(
                "api-province-announcement",
                "서울 전체 검색 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11)
        ));
        persistSearchSupplyRows(seoulComplex, announcement);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/announcements").param("regionCode", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(announcement.getId()));
    }

    @Test
    void 하위_구가_있는_시_지역코드는_소속_구의_공고만_조회한다() throws Exception {
        HousingComplex suwonComplex = persist(createHousingComplex("api-city-suwon", "41111"));
        HousingComplex seongnamComplex = persist(createHousingComplex("api-city-seongnam", "41131"));
        Announcement suwonAnnouncement = persist(createSearchAnnouncement(
                "api-city-suwon-announcement",
                "수원시 검색 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11)
        ));
        Announcement seongnamAnnouncement = persist(createSearchAnnouncement(
                "api-city-seongnam-announcement",
                "성남시 검색 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 13),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11)
        ));
        persistSearchSupplyRows(suwonComplex, suwonAnnouncement);
        persistSearchSupplyRows(seongnamComplex, seongnamAnnouncement);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/announcements").param("regionCode", "41110"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(suwonAnnouncement.getId()));
    }

    @Test
    void 코드_접두사가_같은_다른_군의_공고는_지역_필터에서_제외한다() throws Exception {
        HousingComplex yeongdongComplex = persist(createHousingComplex("api-county-yeongdong", "43740"));
        HousingComplex jeungpyeongComplex = persist(createHousingComplex("api-county-jeungpyeong", "43745"));
        Announcement yeongdongAnnouncement = persist(createSearchAnnouncement(
                "api-county-yeongdong-announcement",
                "영동군 검색 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11)
        ));
        Announcement jeungpyeongAnnouncement = persist(createSearchAnnouncement(
                "api-county-jeungpyeong-announcement",
                "증평군 검색 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 13),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11)
        ));
        persistSearchSupplyRows(yeongdongComplex, yeongdongAnnouncement);
        persistSearchSupplyRows(jeungpyeongComplex, jeungpyeongAnnouncement);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/announcements").param("regionCode", "43740"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(yeongdongAnnouncement.getId()));
    }

    @Test
    void 모든_검색_그룹의_교집합과_같은_그룹의_OR을_HTTP로_반환한다() throws Exception {
        HousingComplex canonicalRegionComplex = persist(createHousingComplex("api-combined-canonical", "12210"));
        HousingComplex differentRegionComplex = persist(createHousingComplex("api-combined-other-region", "11140"));
        Announcement original = persist(createSearchAnnouncement(
                "api-combined-original",
                "통합_검색 원본 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11)
        ));
        Announcement previous = persist(createSearchAnnouncement(
                "api-combined-previous",
                "이전 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11)
        ));
        Announcement correction = persist(createSearchAnnouncement(
                "api-combined-correction",
                "통합_검색 정정 공고",
                previous,
                "api-combined-previous",
                AnnouncementPublicationType.CORRECTION,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.WAITLIST,
                AgencyCode.SH,
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11)
        ));
        Announcement wrongRentalType = persist(createSearchAnnouncement(
                "api-combined-wrong-rental",
                "통합_검색 임대유형 불일치 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.NATIONAL_RENTAL,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 13),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11)
        ));
        Announcement regionOnly = persist(createSearchAnnouncement(
                "api-combined-region-only",
                "다른 키워드 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 13),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11)
        ));
        Announcement wrongRegion = persist(createSearchAnnouncement(
                "api-combined-wrong-region",
                "통합_검색 지역 불일치 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 13),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11)
        ));
        Announcement wrongApplicationStatus = persist(createSearchAnnouncement(
                "api-combined-wrong-status",
                "통합_검색 신청상태 불일치 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 13),
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 8, 15)
        ));
        Announcement wrongApplicationPeriod = persist(createSearchAnnouncement(
                "api-combined-wrong-period",
                "통합_검색 접수기간 불일치 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 13),
                LocalDate.of(2026, 8, 9),
                LocalDate.of(2026, 8, 10)
        ));
        persistSearchSupplyRows(
                canonicalRegionComplex,
                original,
                correction,
                wrongRentalType,
                regionOnly,
                wrongApplicationStatus,
                wrongApplicationPeriod
        );
        persistSearchSupplyRows(differentRegionComplex, wrongRegion);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(combinedSearchRequest().param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(original.getId()))
                .andExpect(jsonPath("$.data.items[1].announcementId").value(correction.getId()))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
    }

    @Test
    void 필터를_유지한_커서는_같은_게시일의_공고를_중복_없이_두_페이지로_나눈다()
            throws Exception {
        HousingComplex canonicalRegionComplex = persist(createHousingComplex("api-cursor-canonical", "12210"));
        Announcement first = persist(createSearchAnnouncement(
                "api-cursor-first",
                "커서_검색 첫째 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12)
        ));
        Announcement second = persist(createSearchAnnouncement(
                "api-cursor-second",
                "커서_검색 둘째 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12)
        ));
        Announcement third = persist(createSearchAnnouncement(
                "api-cursor-third",
                "커서_검색 셋째 공고",
                null,
                null,
                AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING,
                RecruitmentType.NEW,
                AgencyCode.LH,
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 12)
        ));
        persistSearchSupplyRows(canonicalRegionComplex, first, second, third);
        entityManager.flush();
        entityManager.clear();

        MvcResult firstPage = mockMvc.perform(cursorSearchRequest().param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andReturn();
        String nextCursor = nextCursor(firstPage);
        assertFalse(nextCursor.isBlank());

        MvcResult secondPage = mockMvc.perform(cursorSearchRequest()
                        .param("size", "2")
                        .param("cursor", nextCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
                .andReturn();

        assertEquals(List.of(third.getId(), second.getId()), announcementIds(firstPage));
        assertEquals(List.of(first.getId()), announcementIds(secondPage));
    }

    @Test
    void 취소_공고유형_필터는_공개된_INVALID_REQUEST로_거부한다() throws Exception {
        assertPublicError(
                mockMvc.perform(get("/api/v1/announcements").param("publicationTypes", "CANCELLATION")),
                "INVALID_REQUEST"
        );
    }

    @Test
    void 취소_신청상태_필터는_공개된_INVALID_REQUEST로_거부한다() throws Exception {
        assertPublicError(
                mockMvc.perform(get("/api/v1/announcements").param("applicationStatuses", "CANCELLED")),
                "INVALID_REQUEST"
        );
    }

    @Test
    void 역전된_신청기간은_공개된_INVALID_REQUEST로_거부한다() throws Exception {
        assertPublicError(
                mockMvc.perform(get("/api/v1/announcements")
                        .param("applicationFrom", "2026-08-13")
                        .param("applicationTo", "2026-08-12")),
                "INVALID_REQUEST"
        );
    }

    @Test
    void 알수_없는_지역코드는_공개된_INVALID_REGION_CODE로_거부한다() throws Exception {
        assertPublicError(
                mockMvc.perform(get("/api/v1/announcements").param("regionCode", "99999")),
                "INVALID_REGION_CODE"
        );
    }

    @Test
    void 마감순은_접수중_마감일과_예정_시작일_순으로_페이지를_이어준다() throws Exception {
        Announcement soon = persist(deadlineAnnouncement("order-soon", 1, 9, 10));
        Announcement sameDayNew = persist(deadlineAnnouncement("order-same", 2, 9, 10));
        Announcement later = persist(deadlineAnnouncement("order-later", 3, 9, 15));
        Announcement upcoming = persist(deadlineAnnouncement("order-upcoming", 4, 11, 12));
        persist(deadlineAnnouncement("order-upcoming-later", 5, 12, 13));
        persist(deadlineAnnouncement("order-closed", 6, 7, 9));
        entityManager.flush();
        entityManager.clear();

        MvcResult first = mockMvc.perform(get("/api/v1/announcements")
                        .param("keyword", "order-").param("sort", "DEADLINE").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(6))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(sameDayNew.getId()))
                .andExpect(jsonPath("$.data.items[1].announcementId").value(soon.getId()))
                .andExpect(jsonPath("$.data.hasNext").value(true)).andReturn();
        String cursor = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("nextCursor").asText();
        mockMvc.perform(get("/api/v1/announcements")
                        .param("keyword", "order-").param("sort", "DEADLINE")
                        .param("size", "2").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(6))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(later.getId()))
                .andExpect(jsonPath("$.data.items[1].announcementId").value(upcoming.getId()));
        mockMvc.perform(get("/api/v1/announcements").param("sort", "LATEST").param("cursor", cursor))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEADLINE", "LATEST", "LATEST_ASC"})
    void 마감임박순은_선택지역의_현재_확정접수_종료일을_쓰고_카드에도_같은_기간을_보여준다(String sort) throws Exception {
        HousingComplex seoul = persist(createHousingComplex("deadline-seoul", "11140"));
        HousingComplex other = persist(createHousingComplex("deadline-other", "29110"));
        Announcement reviewed = persist(deadlineAnnouncement("schedule-order-reviewed", 1, 1, 30));
        reviewed.confirmApplicationPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 30));
        persist(AnnouncementApplicationSchedule.verified(reviewed, seoul, "1순위",
                ApplicationScheduleState.CONFIRMED, null, LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 10),
                null, null, "https://example.com/schedule", 1));
        persist(AnnouncementApplicationSchedule.verified(reviewed, other, "1순위",
                ApplicationScheduleState.CONFIRMED, null, LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 20),
                null, null, "https://example.com/schedule", 2));
        Announcement legacy = persist(deadlineAnnouncement("schedule-order-legacy", 1, 9, 15));
        persistSearchSupplyRows(seoul, reviewed, legacy);
        persist(createSupplyRow(reviewed, other, null, "reviewed-other", 1, 1, null));
        persist(createSupplyRow(legacy, other, null, "legacy-other", 1, 1, null));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/announcements").param("keyword", "schedule-order-")
                        .param("sort", sort).param("regionCode", "11140"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(reviewed.getId()))
                .andExpect(jsonPath("$.data.items[0].applicationStartAt").value("2026-08-09"))
                .andExpect(jsonPath("$.data.items[0].applicationEndAt").value("2026-08-10"));
        mockMvc.perform(get("/api/v1/announcements").param("keyword", "schedule-order-")
                        .param("sort", sort).param("regionCode", "29110"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(legacy.getId()));
    }

    @Test
    void 공고일_오름차순은_같은날_ID까지_정렬하고_방향이_다른_커서를_거부한다() throws Exception {
        Announcement old = persist(deadlineAnnouncement("ascending-old", 1, 9, 10));
        Announcement sameDay = persist(deadlineAnnouncement("ascending-same", 1, 9, 10));
        Announcement recent = persist(deadlineAnnouncement("ascending-recent", 2, 9, 15));
        entityManager.flush();
        entityManager.clear();

        MvcResult first = mockMvc.perform(get("/api/v1/announcements")
                        .param("keyword", "ascending-").param("sort", "LATEST_ASC").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(old.getId())).andReturn();
        String cursor = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("nextCursor").asText();
        mockMvc.perform(get("/api/v1/announcements")
                        .param("keyword", "ascending-").param("sort", "LATEST_ASC").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(sameDay.getId()))
                .andExpect(jsonPath("$.data.items[1].announcementId").value(recent.getId()))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mockMvc.perform(get("/api/v1/announcements").param("sort", "LATEST").param("cursor", cursor))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 마감일_내림차순은_접수중_우선을_유지하고_날짜와_동률의_순서를_뒤집는다() throws Exception {
        Announcement soon = persist(deadlineAnnouncement("descending-soon", 1, 9, 10));
        Announcement sameDay = persist(deadlineAnnouncement("descending-same", 2, 9, 10));
        Announcement later = persist(deadlineAnnouncement("descending-later", 3, 9, 15));
        Announcement upcoming = persist(deadlineAnnouncement("descending-upcoming", 4, 11, 12));
        entityManager.flush();
        entityManager.clear();

        MvcResult first = mockMvc.perform(get("/api/v1/announcements")
                        .param("keyword", "descending-").param("sort", "DEADLINE_DESC").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(4))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(later.getId()))
                .andExpect(jsonPath("$.data.items[1].announcementId").value(soon.getId())).andReturn();
        String cursor = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("nextCursor").asText();
        mockMvc.perform(get("/api/v1/announcements")
                        .param("keyword", "descending-").param("sort", "DEADLINE_DESC").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(4))
                .andExpect(jsonPath("$.data.items[0].announcementId").value(sameDay.getId()))
                .andExpect(jsonPath("$.data.items[1].announcementId").value(upcoming.getId()))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mockMvc.perform(get("/api/v1/announcements").param("sort", "DEADLINE").param("cursor", cursor))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 알수없는_공고정렬은_거부한다() throws Exception {
        mockMvc.perform(get("/api/v1/announcements").param("sort", "UNKNOWN"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LATEST", "LATEST_ASC"})
    void 최신순의_같은_게시일은_방향과_무관하게_마감임박순으로_페이지를_이어준다(String sort) throws Exception {
        Announcement later = persist(deadlineAnnouncement("latest-tie-later", 1, 9, 20));
        Announcement soon = persist(deadlineAnnouncement("latest-tie-soon", 1, 9, 10));
        Announcement sameDeadline = persist(deadlineAnnouncement("latest-tie-same", 1, 9, 10));
        Announcement upcoming = persist(deadlineAnnouncement("latest-tie-upcoming", 1, 11, 12));
        Announcement recent = persist(deadlineAnnouncement("latest-tie-recent", 2, 9, 15));
        entityManager.flush();
        entityManager.clear();

        List<Long> expected = List.of(recent.getId(), sameDeadline.getId(), soon.getId(),
                later.getId(), upcoming.getId());
        if (sort.equals("LATEST_ASC")) {
            expected = List.of(soon.getId(), sameDeadline.getId(), later.getId(), upcoming.getId(), recent.getId());
        }
        String cursor = null;
        List<Long> actual = new ArrayList<>();
        for (int index = 0; index < expected.size(); index++) {
            MockHttpServletRequestBuilder request = get("/api/v1/announcements")
                    .param("keyword", "latest-tie-").param("sort", sort).param("size", "1");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode data = objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).path("data");
            actual.add(data.path("items").get(0).path("announcementId").asLong());
            assertEquals(index < expected.size() - 1, data.path("hasNext").asBoolean());
            cursor = data.path("nextCursor").asText();
        }
        assertEquals(expected, actual);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LATEST", "LATEST_ASC"})
    void 이전_최신순_커서의_조회는_기존_ID_순서로_이어서_반환한다(String sort) throws Exception {
        Announcement later = persist(deadlineAnnouncement("legacy-order-later", 1, 9, 20));
        Announcement soon = persist(deadlineAnnouncement("legacy-order-soon", 1, 9, 10));
        Announcement upcoming = persist(deadlineAnnouncement("legacy-order-upcoming", 1, 11, 12));
        entityManager.flush();
        entityManager.clear();
        String version = "v1";
        long anchor = upcoming.getId();
        List<Long> expected = List.of(soon.getId(), later.getId());
        if (sort.equals("LATEST_ASC")) {
            version = "v1-asc";
            anchor = later.getId();
            expected = List.of(soon.getId(), upcoming.getId());
        }
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (version + "|2026-08-01|" + anchor).getBytes(StandardCharsets.UTF_8));
        List<Long> actual = new ArrayList<>();
        for (int index = 0; index < expected.size(); index++) {
            JsonNode data = objectMapper.readTree(mockMvc.perform(get("/api/v1/announcements")
                            .param("keyword", "legacy-order-").param("sort", sort).param("size", "1")
                            .param("cursor", cursor)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).path("data");
            actual.add(data.path("items").get(0).path("announcementId").asLong());
            assertEquals(index == 0, data.path("hasNext").asBoolean());
            cursor = data.path("nextCursor").asText();
        }
        assertEquals(expected, actual);
    }

    private Announcement deadlineAnnouncement(String name, int postedDay, int startDay, int endDay) {
        return createSearchAnnouncement(name, name, null, null, AnnouncementPublicationType.ORIGINAL,
                RentalType.HAPPY_HOUSING, RecruitmentType.NEW, AgencyCode.LH,
                LocalDate.of(2026, 8, postedDay), LocalDate.of(2026, 8, startDay),
                LocalDate.of(2026, 8, endDay));
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

    private Announcement createSearchAnnouncement(
            String sourceIdentifier,
            String name,
            Announcement previousAnnouncement,
            String previousSourceIdentifier,
            AnnouncementPublicationType publicationType,
            RentalType rentalType,
            RecruitmentType recruitmentType,
            AgencyCode agencyCode,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate
    ) {
        return Announcement.create(
                sourceIdentifier,
                previousSourceIdentifier,
                previousAnnouncement,
                name,
                publicationType,
                rentalType,
                recruitmentType,
                agencyCode,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                LocalDate.of(2026, 8, 20),
                "https://example.com/announcements/" + sourceIdentifier,
                null,
                0L,
                ReceptionPlace.create(
                        "검색 접수처",
                        ReceptionMethod.ONLINE,
                        null,
                        "1600-1004",
                        "https://apply.lh.or.kr"
                )
        );
    }

    private HousingComplex createHousingComplex() {
        return createHousingComplex("api-complex", "11140");
    }

    private HousingComplex createHousingComplex(String sourceIdentifier, String cityCountyDistrictCode) {
        return HousingComplex.create(
                "서울 행복주택",
                sourceIdentifier,
                "행복주택",
                Address.create(
                        "서울특별시 중구 세종대로 110",
                        "1114010100100010000",
                        "1114010100",
                        cityCountyDistrictCode.substring(0, 2),
                        cityCountyDistrictCode,
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

    private void persistSearchSupplyRows(HousingComplex housingComplex, Announcement... announcements) {
        for (int index = 0; index < announcements.length; index++) {
            Announcement announcement = announcements[index];
            persist(createSupplyRow(
                    announcement,
                    housingComplex,
                    null,
                    announcement.getSourceAnnouncementIdentifier() + "-supply",
                    index,
                    1,
                    null
            ));
        }
    }

    private MockHttpServletRequestBuilder combinedSearchRequest() {
        return get("/api/v1/announcements")
                .param("keyword", "  통합_검색  ")
                .param("regionCode", "29110")
                .param("rentalTypes", "HAPPY_HOUSING")
                .param("applicationStatuses", "APPLYING")
                .param("publicationTypes", "ORIGINAL", "CORRECTION")
                .param("agencyCodes", "LH", "SH")
                .param("recruitmentTypes", "NEW", "WAITLIST")
                .param("applicationFrom", "2026-08-11")
                .param("applicationTo", "2026-08-11");
    }

    private MockHttpServletRequestBuilder cursorSearchRequest() {
        return get("/api/v1/announcements")
                .param("keyword", "  커서_검색  ")
                .param("regionCode", "29110")
                .param("rentalTypes", "HAPPY_HOUSING")
                .param("applicationStatuses", "APPLYING")
                .param("publicationTypes", "ORIGINAL")
                .param("agencyCodes", "LH")
                .param("recruitmentTypes", "NEW")
                .param("applicationFrom", "2026-08-10")
                .param("applicationTo", "2026-08-12");
    }

    private String nextCursor(MvcResult result) throws Exception {
        return responseData(result).path("nextCursor").asText();
    }

    private List<Long> announcementIds(MvcResult result) throws Exception {
        List<Long> announcementIds = new ArrayList<>();
        for (JsonNode item : responseData(result).path("items")) {
            announcementIds.add(item.path("announcementId").asLong());
        }
        return announcementIds;
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void assertPublicError(ResultActions resultActions, String errorCode) throws Exception {
        String responseBody = resultActions
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(errorCode))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(responseBody.contains("Exception"));
        assertFalse(responseBody.contains("SQL"));
        assertFalse(responseBody.contains("stack"));
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
