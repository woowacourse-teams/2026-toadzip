package com.toadzip.backend.housing.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Import(HousingComplexApiIntegrationTest.FixedClockConfiguration.class)
class HousingComplexApiIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    private static final String SOUTH_WEST_LATITUDE = "37.400000";

    private static final String SOUTH_WEST_LONGITUDE = "126.800000";

    private static final String NORTH_EAST_LATITUDE = "37.600000";

    private static final String NORTH_EAST_LONGITUDE = "127.100000";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MockMvc mockMvc;

    private HousingComplex boundaryComplex;

    private HousingComplex insideComplex;

    private HousingComplex sameDateComplex;

    private HousingComplex outsideComplex;

    private HousingType firstInsideType;

    private HousingType secondInsideType;

    private Announcement correction;

    @BeforeEach
    void setUpFixture() {
        boundaryComplex = persistComplex("남서 경계 단지", SOUTH_WEST_LATITUDE, SOUTH_WEST_LONGITUDE);
        sameDateComplex = persistComplex("동일 게시일 단지", "37.450000", "126.850000");
        insideComplex = persistComplex("경계 안 단지", "37.500000", "126.900000");
        outsideComplex = persistComplex("경계 밖 단지", "37.700000", "126.900000");

        HousingType boundaryType = persistHousingType(
                boundaryComplex,
                "29A",
                "29.00",
                "35.00",
                "https://example.com/29a.png",
                false,
                "70000"
        );
        HousingType sameDateType = persistHousingType(
                sameDateComplex,
                "33A",
                "33.00",
                "39.00",
                "https://example.com/33a.png",
                false,
                "90000"
        );
        firstInsideType = persistHousingType(
                insideComplex,
                "36A",
                "36.12",
                "41.10",
                "https://example.com/36a.png",
                false,
                "100000"
        );
        secondInsideType = persistHousingType(
                insideComplex,
                "44B",
                "44.87",
                "51.10",
                "https://example.com/44b.png",
                true,
                "120000"
        );

        persistCorrectionChain(sameDateType);
        persistCancellationChain(boundaryType);
        persistEndedLeaf();
        persistUnmatchedCurrentRow();
        entityManager.flush();
    }

    @Test
    void 지도는_경계를_포함하고_영역_밖을_제외해_ID_순으로_모두_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/complexes/map")
                        .param("southWestLat", SOUTH_WEST_LATITUDE)
                        .param("southWestLng", SOUTH_WEST_LONGITUDE)
                        .param("northEastLat", NORTH_EAST_LATITUDE)
                        .param("northEastLng", NORTH_EAST_LONGITUDE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[*].complexId", contains(
                        boundaryComplex.getId().intValue(),
                        sameDateComplex.getId().intValue(),
                        insideComplex.getId().intValue()
                )))
                .andExpect(jsonPath("$.data.items[*].complexId", not(hasItem(outsideComplex.getId().intValue()))))
                .andExpect(jsonPath("$.data.items[0].latitude").value(37.400000))
                .andExpect(jsonPath("$.data.items[0].longitude").value(126.800000))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").doesNotExist());
    }

    @Test
    void 목록은_정정_leaf를_대표로_삼고_커서_다음_페이지와_겹치지_않는다() throws Exception {
        MvcResult firstPage = mockMvc.perform(get("/api/v1/complexes")
                        .param("size", "1")
                        .param("southWestLat", SOUTH_WEST_LATITUDE)
                        .param("southWestLng", SOUTH_WEST_LONGITUDE)
                        .param("northEastLat", NORTH_EAST_LATITUDE)
                        .param("northEastLng", NORTH_EAST_LONGITUDE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].complexId").value(insideComplex.getId()))
                .andExpect(jsonPath("$.data.items[0].regionName").value("서울특별시 중구"))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.announcementId")
                        .value(correction.getId()))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.publicationType")
                        .value("CORRECTION"))
                .andExpect(jsonPath("$.data.items[0].depositMin").value(10000000))
                .andExpect(jsonPath("$.data.items[0].depositMax").value(30000000))
                .andExpect(jsonPath("$.data.items[0].monthlyRentMin").value(100000))
                .andExpect(jsonPath("$.data.items[0].monthlyRentMax").value(300000))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andReturn();

        String firstBody = firstPage.getResponse().getContentAsString();
        String nextCursor = JsonPath.read(firstBody, "$.data.nextCursor");
        Integer firstComplexId = JsonPath.read(firstBody, "$.data.items[0].complexId");

        MvcResult secondPage = mockMvc.perform(get("/api/v1/complexes")
                        .param("cursor", nextCursor)
                        .param("size", "1")
                        .param("southWestLat", SOUTH_WEST_LATITUDE)
                        .param("southWestLng", SOUTH_WEST_LONGITUDE)
                        .param("northEastLat", NORTH_EAST_LATITUDE)
                        .param("northEastLng", NORTH_EAST_LONGITUDE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].complexId").value(sameDateComplex.getId()))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement.announcementId")
                        .value(correction.getId()))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andReturn();

        String secondBody = secondPage.getResponse().getContentAsString();
        Integer secondComplexId = JsonPath.read(secondBody, "$.data.items[0].complexId");
        assertNotEquals(firstComplexId, secondComplexId);

        String secondCursor = JsonPath.read(secondBody, "$.data.nextCursor");
        mockMvc.perform(get("/api/v1/complexes")
                        .param("cursor", secondCursor)
                        .param("size", "1")
                        .param("southWestLat", SOUTH_WEST_LATITUDE)
                        .param("southWestLng", SOUTH_WEST_LONGITUDE)
                        .param("northEastLat", NORTH_EAST_LATITUDE)
                        .param("northEastLng", NORTH_EAST_LONGITUDE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].complexId").value(boundaryComplex.getId()))
                .andExpect(jsonPath("$.data.items[0].representativeAnnouncement").value(nullValue()))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
    }

    @Test
    void 상세는_좌표와_정렬된_주택형_정정_leaf의_현재_공급조건만_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/complexes/{complexId}", insideComplex.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address.regionName").value("서울특별시 중구"))
                .andExpect(jsonPath("$.data.address.latitude").value(37.500000))
                .andExpect(jsonPath("$.data.address.longitude").value(126.900000))
                .andExpect(jsonPath("$.data.housingTypes.length()").value(2))
                .andExpect(jsonPath("$.data.housingTypes[*].housingTypeId", contains(
                        firstInsideType.getId().intValue(),
                        secondInsideType.getId().intValue()
                )))
                .andExpect(jsonPath("$.data.housingTypes[0].currentSupplyConditions.length()").value(2))
                .andExpect(jsonPath("$.data.housingTypes[0].currentSupplyConditions[0].target").value("대학생"))
                .andExpect(jsonPath("$.data.housingTypes[0].currentSupplyConditions[0].deposit")
                        .value(10000000))
                .andExpect(jsonPath("$.data.housingTypes[0].currentSupplyConditions[0].monthlyRent")
                        .value(100000))
                .andExpect(jsonPath("$.data.housingTypes[0].currentSupplyConditions[1].target").value("청년"))
                .andExpect(jsonPath("$.data.housingTypes[0].currentSupplyConditions[1].deposit")
                        .value(20000000))
                .andExpect(jsonPath("$.data.housingTypes[1].currentSupplyConditions.length()").value(1))
                .andExpect(jsonPath("$.data.housingTypes[1].currentSupplyConditions[0].target")
                        .value("신혼부부"))
                .andExpect(jsonPath("$.data.housingTypes[1].currentSupplyConditions[0].deposit")
                        .value(30000000))
                .andExpect(jsonPath("$.data.housingTypes[1].currentSupplyConditions[0].monthlyRent")
                        .value(300000))
                .andExpect(jsonPath("$.data.housingTypes[1].currentSupplyConditions[0].convertibleDeposit")
                        .value(3000000))
                .andExpect(jsonPath("$.data.currentAnnouncements.length()").value(1))
                .andExpect(jsonPath("$.data.currentAnnouncements[0].announcementId").value(correction.getId()))
                .andExpect(jsonPath("$.data.currentAnnouncements[0].publicationType").value("CORRECTION"))
                .andExpect(jsonPath("$.data.currentAnnouncements[0].actualCompetitionRate").value(2.5))
                .andExpect(jsonPath("$.data.currentAnnouncements[0].targets", contains(
                        "대학생",
                        "청년",
                        "신혼부부"
                )))
                .andExpect(content().string(not(containsString("정정 전 대상"))))
                .andExpect(content().string(not(containsString("취소 전 대상"))))
                .andExpect(content().string(not(containsString("취소 공고 대상"))))
                .andExpect(content().string(not(containsString("종료 대상"))))
                .andExpect(content().string(not(containsString("미매칭 대상"))));
    }

    private void persistCorrectionChain(HousingType sameDateType) {
        Announcement original = persistAnnouncement(
                null,
                "ORIGINAL",
                "correction-original",
                TODAY.minusDays(10),
                TODAY.minusDays(2),
                TODAY.plusDays(3)
        );
        SupplyRow originalRow = persistSupplyRow(
                original,
                insideComplex,
                firstInsideType,
                "correction-original-row",
                1
        );
        persistSupplyTarget(originalRow, "정정 전 대상", "90000000", "900000", null, 1);

        correction = persistAnnouncement(
                original,
                "CORRECTION",
                "correction",
                TODAY.minusDays(1),
                TODAY.minusDays(1),
                TODAY.plusDays(3),
                new BigDecimal("2.5000")
        );
        SupplyRow firstCorrectionRow = persistSupplyRow(
                correction,
                insideComplex,
                firstInsideType,
                "correction-first-row",
                1
        );
        persistSupplyTarget(firstCorrectionRow, "대학생", "10000000", "100000", null, 1);
        persistSupplyTarget(firstCorrectionRow, "청년", "20000000", "200000", null, 2);
        SupplyRow secondCorrectionRow = persistSupplyRow(
                correction,
                insideComplex,
                secondInsideType,
                "correction-second-row",
                2
        );
        persistSupplyTarget(secondCorrectionRow, "신혼부부", "30000000", "300000", "3000000", 1);
        SupplyRow sameDateCorrectionRow = persistSupplyRow(
                correction,
                sameDateComplex,
                sameDateType,
                "correction-same-date-row",
                1
        );
        persistSupplyTarget(sameDateCorrectionRow, "일반", "15000000", "150000", null, 1);
    }

    private void persistCancellationChain(HousingType boundaryType) {
        Announcement original = persistAnnouncement(
                null,
                "ORIGINAL",
                "cancelled-original",
                TODAY.minusDays(8),
                TODAY.minusDays(1),
                TODAY.plusDays(2)
        );
        persistCancellationRows(original, boundaryType, "cancelled-original", "취소 전 대상");

        Announcement cancellation = persistAnnouncement(
                original,
                "CANCELLATION",
                "cancellation",
                TODAY,
                TODAY.minusDays(1),
                TODAY.plusDays(2)
        );
        persistCancellationRows(cancellation, boundaryType, "cancellation", "취소 공고 대상");
    }

    private void persistCancellationRows(
            Announcement announcement,
            HousingType boundaryType,
            String sourcePrefix,
            String target
    ) {
        SupplyRow boundaryRow = persistSupplyRow(
                announcement,
                boundaryComplex,
                boundaryType,
                sourcePrefix + "-boundary-row",
                1
        );
        persistSupplyTarget(boundaryRow, target, "70000000", "700000", null, 1);
        SupplyRow insideRow = persistSupplyRow(
                announcement,
                insideComplex,
                firstInsideType,
                sourcePrefix + "-inside-row",
                2
        );
        persistSupplyTarget(insideRow, target, "80000000", "800000", null, 1);
    }

    private void persistEndedLeaf() {
        Announcement ended = persistAnnouncement(
                null,
                "ORIGINAL",
                "ended",
                TODAY.minusDays(2),
                TODAY.minusDays(4),
                TODAY.minusDays(1)
        );
        SupplyRow endedRow = persistSupplyRow(
                ended,
                insideComplex,
                firstInsideType,
                "ended-row",
                1
        );
        persistSupplyTarget(endedRow, "종료 대상", "60000000", "600000", null, 1);
    }

    private void persistUnmatchedCurrentRow() {
        Announcement unmatched = persistAnnouncement(
                null,
                "ORIGINAL",
                "unmatched",
                TODAY,
                TODAY.minusDays(1),
                TODAY.plusDays(2)
        );
        SupplyRow unmatchedRow = persistUnmatchedSupplyRow(unmatched);
        persistSupplyTarget(unmatchedRow, "미매칭 대상", "50000000", "500000", null, 1);
    }

    private HousingComplex persistComplex(String name, String latitude, String longitude) {
        HousingComplex complex = HousingComplex.create(
                name,
                "source-" + name,
                "행복주택",
                Address.create(
                        "서울특별시 중구 세종대로 110",
                        "1114010100100010000",
                        "1114010100",
                        "11",
                        "11140",
                        new BigDecimal(latitude),
                        new BigDecimal(longitude)
                ),
                100,
                "LH",
                LocalDate.of(2020, 1, 1),
                "개별난방",
                "아파트",
                "계단식",
                true,
                80,
                "https://example.com/" + name + ".png",
                7
        );
        entityManager.persist(complex);
        return complex;
    }

    private HousingType persistHousingType(
            HousingComplex complex,
            String name,
            String exclusiveArea,
            String supplyArea,
            String floorPlanUrl,
            Boolean duplex,
            String maintenanceFee
    ) {
        HousingType housingType = HousingType.create(
                complex,
                name,
                new BigDecimal(exclusiveArea),
                new BigDecimal(supplyArea),
                50,
                floorPlanUrl,
                duplex,
                new BigDecimal(maintenanceFee)
        );
        entityManager.persist(housingType);
        return housingType;
    }

    private Announcement persistAnnouncement(
            Announcement previous,
            String status,
            String suffix,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate
    ) {
        return persistAnnouncement(
                previous,
                status,
                suffix,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                null
        );
    }

    private Announcement persistAnnouncement(
            Announcement previous,
            String status,
            String suffix,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            BigDecimal actualCompetitionRate
    ) {
        String previousSourceIdentifier = null;
        if (previous != null) {
            previousSourceIdentifier = previous.getSourceAnnouncementIdentifier();
        }
        Announcement announcement = Announcement.create(
                "source-" + suffix,
                previousSourceIdentifier,
                previous,
                "공고 " + suffix,
                status,
                "행복주택",
                "신규모집",
                "LH",
                postedDate,
                applicationStartDate,
                applicationEndDate,
                applicationEndDate.plusMonths(1),
                "https://example.com/announcements/" + suffix,
                null,
                0,
                actualCompetitionRate,
                null,
                ReceptionPlace.create("LH 청약센터", "인터넷", null, "1600-1004", null)
        );
        entityManager.persist(announcement);
        return announcement;
    }

    private SupplyRow persistSupplyRow(
            Announcement announcement,
            HousingComplex complex,
            HousingType housingType,
            String sourceIdentifier,
            int displayOrder
    ) {
        SupplyRow supplyRow = SupplyRow.create(
                announcement,
                complex,
                housingType,
                sourceIdentifier,
                displayOrder,
                complex.getName(),
                housingType.getName(),
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                null,
                10
        );
        entityManager.persist(supplyRow);
        return supplyRow;
    }

    private SupplyRow persistUnmatchedSupplyRow(Announcement announcement) {
        SupplyRow supplyRow = SupplyRow.create(
                announcement,
                null,
                null,
                "unmatched-row",
                1,
                "매칭되지 않은 단지",
                "36A",
                "1114010100100010000",
                YearMonth.of(2027, 3),
                SupplyCategory.NEW_SUPPLY,
                "단지 매칭 실패",
                10
        );
        entityManager.persist(supplyRow);
        return supplyRow;
    }

    private void persistSupplyTarget(
            SupplyRow supplyRow,
            String target,
            String deposit,
            String monthlyRent,
            String convertibleDeposit,
            int displayOrder
    ) {
        BigDecimal convertedDeposit = null;
        if (convertibleDeposit != null) {
            convertedDeposit = new BigDecimal(convertibleDeposit);
        }
        entityManager.persist(SupplyTarget.create(
                supplyRow,
                target,
                "1순위",
                5,
                5,
                new BigDecimal(deposit),
                new BigDecimal(monthlyRent),
                convertedDeposit,
                "신청 조건",
                displayOrder
        ));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedSeoulClock() {
            return Clock.fixed(
                    Instant.parse("2026-08-26T15:00:00Z"),
                    ZoneId.of("Asia/Seoul")
            );
        }
    }
}
