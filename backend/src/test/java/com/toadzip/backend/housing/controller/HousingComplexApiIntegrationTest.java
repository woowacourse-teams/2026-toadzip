package com.toadzip.backend.housing.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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
import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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

    private static final String SEARCH_SOUTH_WEST_LATITUDE = "35.000000";

    private static final String SEARCH_SOUTH_WEST_LONGITUDE = "126.700000";

    private static final String SEARCH_NORTH_EAST_LATITUDE = "35.500000";

    private static final String SEARCH_NORTH_EAST_LONGITUDE = "127.200000";

    private static final int MAX_FILTERED_LIST_PAGES = 10;

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

    private HousingComplex matchingFirstComplex;

    private HousingComplex matchingSecondComplex;

    private HousingComplex matchingThirdComplex;

    private HousingComplex filterExcludedComplex;

    private Map<FullFilterSentinel, HousingComplex> fullFilterSentinels;

    private HousingComplex firstNullSortComplex;

    private HousingComplex secondNullSortComplex;

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
        persistSearchFixture();
        entityManager.flush();
    }

    @Test
    void 같은_전체_검색조건의_목록과_지도는_같은_단지_ID_집합을_반환한다() throws Exception {
        List<Long> listIds = fetchEveryFilteredListPage();
        List<Long> mapIds = fetchFilteredMapIds();

        Set<Long> expectedIds = Set.of(
                matchingFirstComplex.getId(),
                matchingSecondComplex.getId(),
                matchingThirdComplex.getId()
        );
        Set<Long> sentinelIds = fullFilterSentinels.values().stream()
                .map(HousingComplex::getId)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(expectedIds, new HashSet<>(listIds));
        assertEquals(expectedIds, new HashSet<>(mapIds));
        assertEquals(List.of(
                matchingFirstComplex.getId(),
                matchingSecondComplex.getId(),
                matchingThirdComplex.getId()
        ), mapIds);
        assertTrue(listIds.stream().noneMatch(sentinelIds::contains));
        assertTrue(mapIds.stream().noneMatch(sentinelIds::contains));
    }

    @ParameterizedTest
    @EnumSource(ComplexSort.class)
    void 다섯_정렬은_동률과_null을_포함한_HTTP_두_페이지를_중복과_누락_없이_잇는다(
            ComplexSort sort
    ) throws Exception {
        MvcResult firstPage = mockMvc.perform(sortRequest(sort, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andReturn();
        String firstBody = firstPage.getResponse().getContentAsString();
        List<Long> firstIds = readComplexIds(firstBody);
        String cursor = JsonPath.read(firstBody, "$.data.nextCursor");

        MvcResult secondPage = mockMvc.perform(sortRequest(sort, cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
                .andReturn();
        List<Long> secondIds = readComplexIds(secondPage.getResponse().getContentAsString());

        Set<Long> overlap = new HashSet<>(firstIds);
        overlap.retainAll(secondIds);
        List<Long> allIds = new ArrayList<>(firstIds);
        allIds.addAll(secondIds);
        assertTrue(overlap.isEmpty());
        assertEquals(expectedSortOrder(sort), allIds);
        assertEquals(searchFixtureIds(), new HashSet<>(allIds));
    }

    @Test
    void v1_최신공고_커서_HTTP_요청은_성공하고_다음_커서를_v2로_발급한다() throws Exception {
        String legacyCursor = encodedCursor(
                "v1|" + TODAY.minusDays(1) + "|" + filterExcludedComplex.getId()
        );

        MvcResult response = mockMvc.perform(sortRequest(ComplexSort.LATEST_ANNOUNCEMENT, legacyCursor, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].complexId").value(matchingFirstComplex.getId()))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andReturn();

        String nextCursor = JsonPath.read(response.getResponse().getContentAsString(), "$.data.nextCursor");
        String payload = new String(Base64.getUrlDecoder().decode(nextCursor), StandardCharsets.UTF_8);
        assertTrue(payload.startsWith("v2|LATEST_ANNOUNCEMENT|"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequestCases")
    void 의미가_잘못된_검색값은_정확한_INVALID_REQUEST_계약으로_반환한다(HttpErrorCase errorCase)
            throws Exception {
        assertHttpError(
                requestFor("/api/v1/complexes", errorCase),
                "INVALID_REQUEST",
                "단지 조회 요청값이 올바르지 않습니다.",
                errorCase.expectedField()
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRegionCases")
    void 잘못된_지역_코드는_정확한_INVALID_REGION_CODE_계약으로_반환한다(HttpErrorCase errorCase)
            throws Exception {
        assertHttpError(
                requestFor("/api/v1/complexes", errorCase),
                "INVALID_REGION_CODE",
                "지역 코드를 확인해 주세요.",
                errorCase.expectedField()
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedBindingCases")
    void 변환할_수_없는_검색값은_정확한_VALIDATION_FAILED와_field를_반환한다(HttpErrorCase errorCase)
            throws Exception {
        assertHttpError(
                requestFor("/api/v1/complexes", errorCase),
                "VALIDATION_FAILED",
                "요청값이 올바르지 않습니다.",
                errorCase.expectedField()
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCursorCases")
    void 잘못된_커서는_정확한_INVALID_CURSOR_계약으로_반환한다(HttpErrorCase errorCase)
            throws Exception {
        assertHttpError(
                requestFor("/api/v1/complexes", errorCase),
                "INVALID_CURSOR",
                "단지 조회 커서가 올바르지 않습니다.",
                errorCase.expectedField()
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBoundsCases")
    void 잘못된_지도_경계는_정확한_INVALID_MAP_BOUNDS_계약으로_반환한다(HttpErrorCase errorCase)
            throws Exception {
        assertHttpError(
                requestFor("/api/v1/complexes/map", errorCase),
                "INVALID_MAP_BOUNDS",
                "지도 범위 좌표가 올바르지 않습니다.",
                errorCase.expectedField()
        );
    }

    @Test
    void 잘못된_bounds는_filter와_cursor_오류보다_먼저_INVALID_MAP_BOUNDS로_반환한다() throws Exception {
        assertHttpError(
                get("/api/v1/complexes")
                        .param("keyword", " ")
                        .param("cursor", "bad!")
                        .param("southWestLng", SOUTH_WEST_LONGITUDE)
                        .param("northEastLat", NORTH_EAST_LATITUDE)
                        .param("northEastLng", NORTH_EAST_LONGITUDE),
                "INVALID_MAP_BOUNDS",
                "지도 범위 좌표가 올바르지 않습니다.",
                null
        );
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

    private void persistSearchFixture() {
        matchingFirstComplex = persistSearchComplex(
                "통합 검색 커서 정렬 A 단지",
                "35.100000",
                "126.900000",
                "HAPPY_HOUSING",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        persistSearchSupply(
                matchingFirstComplex,
                "filter-a",
                "행복주택",
                "신규모집",
                "LH",
                TODAY.minusDays(1),
                TODAY.minusDays(1),
                TODAY.plusDays(1),
                "40.00",
                "15000000",
                "150000"
        );
        matchingSecondComplex = persistSearchComplex(
                "통합 검색 커서 정렬 B 단지",
                "35.200000",
                "127.000000",
                "국민임대",
                "서울주택도시공사",
                LocalDate.of(2021, 1, 1),
                true
        );
        persistSearchSupply(
                matchingSecondComplex,
                "filter-b",
                "국민임대",
                "예비입주자",
                "서울주택도시공사",
                TODAY.minusDays(2),
                TODAY.minusDays(4),
                TODAY.minusDays(1),
                "45.00",
                "20000000",
                "200000"
        );
        matchingThirdComplex = persistSearchComplex(
                "통합 검색 C 단지",
                "35.250000",
                "127.050000",
                "HAPPY_HOUSING",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        persistSearchSupply(
                matchingThirdComplex,
                "filter-c",
                "행복주택",
                "신규모집",
                "LH",
                TODAY.minusDays(1),
                TODAY.minusDays(1),
                TODAY.plusDays(1),
                "42.00",
                "18000000",
                "180000"
        );

        fullFilterSentinels = new EnumMap<>(FullFilterSentinel.class);
        for (FullFilterSentinel sentinel : FullFilterSentinel.values()) {
            fullFilterSentinels.put(sentinel, persistFullFilterSentinel(sentinel));
        }
        filterExcludedComplex = fullFilterSentinels.get(FullFilterSentinel.KEYWORD);

        firstNullSortComplex = persistSearchComplex(
                "커서 정렬 null A 단지",
                "35.350000",
                "127.120000",
                "HAPPY_HOUSING",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
        secondNullSortComplex = persistSearchComplex(
                "커서 정렬 null B 단지",
                "35.400000",
                "127.150000",
                "HAPPY_HOUSING",
                "LH",
                LocalDate.of(2020, 1, 1),
                true
        );
    }

    private HousingComplex persistFullFilterSentinel(FullFilterSentinel sentinel) {
        String suffix = sentinel.name().toLowerCase(Locale.ROOT);
        String name = "통합 검색 sentinel " + suffix;
        String roadAddress = "전남광주통합특별시 동구 통합 검색로 1";
        String latitude = "35.300000";
        String longitude = "127.100000";
        String complexSupplyType = "HAPPY_HOUSING";
        String announcementSupplyType = "행복주택";
        String complexProvider = "LH";
        String announcementProvider = "LH";
        String provinceCode = "12";
        String districtCode = "12210";
        LocalDate completionDate = LocalDate.of(2020, 1, 1);
        boolean elevatorInstalled = true;
        String recruitmentType = "신규모집";
        LocalDate applicationStartDate = TODAY.minusDays(1);
        LocalDate applicationEndDate = TODAY.plusDays(1);
        String exclusiveArea = "40.00";
        String deposit = "15000000";
        String monthlyRent = "150000";

        switch (sentinel) {
            case KEYWORD -> {
                name = "커서 정렬 keyword sentinel";
                roadAddress = "전남광주특별시 동구 다른길 1";
            }
            case REGION -> districtCode = "12240";
            case RENTAL_TYPE -> {
                complexSupplyType = "PERMANENT_RENTAL";
                announcementSupplyType = "영구임대";
            }
            case APPLICATION_STATUS -> {
                applicationStartDate = TODAY.plusDays(1);
                applicationEndDate = TODAY.plusDays(2);
            }
            case AGENCY -> {
                complexProvider = "GH";
                announcementProvider = "GH";
            }
            case RECRUITMENT_TYPE -> recruitmentType = "기타";
            case MIN_DEPOSIT -> deposit = "9999999";
            case MAX_DEPOSIT -> deposit = "25000001";
            case MIN_MONTHLY_RENT -> monthlyRent = "99999";
            case MAX_MONTHLY_RENT -> monthlyRent = "250001";
            case MIN_EXCLUSIVE_AREA -> exclusiveArea = "38.99";
            case MAX_EXCLUSIVE_AREA -> exclusiveArea = "46.01";
            case BUILT_YEAR_FROM -> completionDate = LocalDate.of(2019, 12, 31);
            case BUILT_YEAR_TO -> completionDate = LocalDate.of(2022, 1, 1);
            case ELEVATOR -> elevatorInstalled = false;
            case SOUTH_WEST_LATITUDE -> latitude = "34.999999";
            case NORTH_EAST_LATITUDE -> latitude = "35.500001";
            case SOUTH_WEST_LONGITUDE -> longitude = "126.699999";
            case NORTH_EAST_LONGITUDE -> longitude = "127.200001";
        }

        HousingComplex complex = persistComplex(
                name,
                latitude,
                longitude,
                complexSupplyType,
                complexProvider,
                completionDate,
                elevatorInstalled,
                provinceCode,
                districtCode,
                roadAddress
        );
        persistSearchSupply(
                complex,
                "sentinel-" + suffix,
                announcementSupplyType,
                recruitmentType,
                announcementProvider,
                TODAY.minusDays(1),
                applicationStartDate,
                applicationEndDate,
                exclusiveArea,
                deposit,
                monthlyRent
        );
        return complex;
    }

    private HousingComplex persistSearchComplex(
            String name,
            String latitude,
            String longitude,
            String supplyType,
            String provider,
            LocalDate completionDate,
            boolean elevatorInstalled
    ) {
        return persistComplex(
                name,
                latitude,
                longitude,
                supplyType,
                provider,
                completionDate,
                elevatorInstalled,
                "12",
                "12210",
                "전남광주통합특별시 동구 통합로 1"
        );
    }

    private void persistSearchSupply(
            HousingComplex complex,
            String suffix,
            String supplyType,
            String recruitmentType,
            String provider,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            String exclusiveArea,
            String deposit,
            String monthlyRent
    ) {
        HousingType housingType = persistHousingType(
                complex,
                suffix + "-type",
                exclusiveArea,
                exclusiveArea,
                "https://example.com/" + suffix + ".png",
                false,
                "100000"
        );
        Announcement announcement = persistAnnouncement(
                null,
                "ORIGINAL",
                suffix,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                null,
                supplyType,
                recruitmentType,
                provider
        );
        SupplyRow supplyRow = persistSupplyRow(announcement, complex, housingType, suffix + "-row", 1);
        persistSupplyTarget(supplyRow, suffix + "-target", deposit, monthlyRent, null, 1);
    }

    private List<Long> fetchEveryFilteredListPage() throws Exception {
        List<Long> ids = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        boolean hasNext = true;
        int pageCount = 0;
        while (hasNext) {
            pageCount++;
            if (pageCount > MAX_FILTERED_LIST_PAGES) {
                fail("전체 filter 목록 조회가 최대 페이지 수를 초과했습니다.");
            }
            MockHttpServletRequestBuilder request = allFilterRequest("/api/v1/complexes")
                    .param("sort", "DEPOSIT_ASC")
                    .param("size", "1");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            MvcResult response = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn();
            String body = response.getResponse().getContentAsString();
            ids.addAll(readComplexIds(body));
            hasNext = JsonPath.read(body, "$.data.hasNext");
            String nextCursor = JsonPath.read(body, "$.data.nextCursor");
            if (hasNext) {
                assertFalse(nextCursor == null || nextCursor.isBlank());
                assertTrue(seenCursors.add(nextCursor), "반복된 next cursor를 반환했습니다.");
            } else {
                assertNull(nextCursor);
            }
            cursor = nextCursor;
        }
        return ids;
    }

    private List<Long> fetchFilteredMapIds() throws Exception {
        MvcResult response = mockMvc.perform(allFilterRequest("/api/v1/complexes/map"))
                .andExpect(status().isOk())
                .andReturn();
        return readComplexIds(response.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder allFilterRequest(String path) {
        return get(path)
                .param("keyword", "통합 검색")
                .param("regionCode", "29110")
                .param("rentalTypes", "HAPPY_HOUSING", "NATIONAL_RENTAL")
                .param("applicationStatuses", "APPLYING", "CLOSED")
                .param("agencyCodes", "LH", "SH")
                .param("recruitmentTypes", "NEW", "WAITLIST")
                .param("minDeposit", "10000000")
                .param("maxDeposit", "25000000")
                .param("minMonthlyRent", "100000")
                .param("maxMonthlyRent", "250000")
                .param("minExclusiveArea", "39.00")
                .param("maxExclusiveArea", "46.00")
                .param("builtYearFrom", "2020")
                .param("builtYearTo", "2021")
                .param("hasElevator", "true")
                .param("southWestLat", SEARCH_SOUTH_WEST_LATITUDE)
                .param("southWestLng", SEARCH_SOUTH_WEST_LONGITUDE)
                .param("northEastLat", SEARCH_NORTH_EAST_LATITUDE)
                .param("northEastLng", SEARCH_NORTH_EAST_LONGITUDE);
    }

    private MockHttpServletRequestBuilder sortRequest(ComplexSort sort, String cursor) {
        return sortRequest(sort, cursor, "3");
    }

    private MockHttpServletRequestBuilder sortRequest(ComplexSort sort, String cursor, String size) {
        MockHttpServletRequestBuilder request = get("/api/v1/complexes")
                .param("keyword", "커서 정렬")
                .param("regionCode", "29110")
                .param("sort", sort.name())
                .param("size", size)
                .param("southWestLat", SEARCH_SOUTH_WEST_LATITUDE)
                .param("southWestLng", SEARCH_SOUTH_WEST_LONGITUDE)
                .param("northEastLat", SEARCH_NORTH_EAST_LATITUDE)
                .param("northEastLng", SEARCH_NORTH_EAST_LONGITUDE);
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return request;
    }

    private List<Long> readComplexIds(String body) {
        List<Number> rawIds = JsonPath.read(body, "$.data.items[*].complexId");
        return rawIds.stream().map(Number::longValue).toList();
    }

    private List<Long> expectedSortOrder(ComplexSort sort) {
        return switch (sort) {
            case LATEST_ANNOUNCEMENT, DEPOSIT_ASC, MONTHLY_RENT_ASC -> List.of(
                    filterExcludedComplex.getId(),
                    matchingFirstComplex.getId(),
                    matchingSecondComplex.getId(),
                    secondNullSortComplex.getId(),
                    firstNullSortComplex.getId()
            );
            case AREA_DESC -> List.of(
                    matchingSecondComplex.getId(),
                    filterExcludedComplex.getId(),
                    matchingFirstComplex.getId(),
                    secondNullSortComplex.getId(),
                    firstNullSortComplex.getId()
            );
            case COMPLETION_DATE_DESC -> List.of(
                    matchingSecondComplex.getId(),
                    secondNullSortComplex.getId(),
                    firstNullSortComplex.getId(),
                    filterExcludedComplex.getId(),
                    matchingFirstComplex.getId()
            );
        };
    }

    private Set<Long> searchFixtureIds() {
        return Set.of(
                matchingFirstComplex.getId(),
                matchingSecondComplex.getId(),
                filterExcludedComplex.getId(),
                firstNullSortComplex.getId(),
                secondNullSortComplex.getId()
        );
    }

    private void assertHttpError(
            MockHttpServletRequestBuilder request,
            String expectedCode,
            String expectedMessage,
            String expectedField
    ) throws Exception {
        MvcResult response = mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andReturn();
        String body = response.getResponse().getContentAsString();
        Map<String, Object> document = JsonPath.read(body, "$");
        assertEquals(expectedCode, document.get("code"));
        assertEquals(expectedMessage, document.get("message"));
        String traceId = (String) document.get("traceId");
        assertTrue(traceId != null && !traceId.isBlank());
        if ("VALIDATION_FAILED".equals(expectedCode)) {
            assertEquals(Set.of("code", "message", "traceId", "errors"), document.keySet());
            List<Map<String, Object>> errors = JsonPath.read(body, "$.errors");
            assertEquals(1, errors.size());
            Map<String, Object> error = errors.getFirst();
            assertEquals(Set.of("field", "reason"), error.keySet());
            assertEquals(expectedField, error.get("field"));
            assertEquals("형식이 올바르지 않습니다.", error.get("reason"));
        } else {
            assertEquals(Set.of("code", "message", "traceId"), document.keySet());
        }
        assertNoInternalDetails(body);
    }

    private MockHttpServletRequestBuilder requestFor(String path, HttpErrorCase errorCase) {
        MockHttpServletRequestBuilder request = get(path);
        if (errorCase.addDefaultBounds()) {
            addDefaultBounds(request);
        }
        List<String> parameters = errorCase.parameterPairs();
        for (int index = 0; index < parameters.size(); index += 2) {
            request.param(parameters.get(index), parameters.get(index + 1));
        }
        return request;
    }

    private void addDefaultBounds(MockHttpServletRequestBuilder request) {
        request.param("southWestLat", SOUTH_WEST_LATITUDE)
                .param("southWestLng", SOUTH_WEST_LONGITUDE)
                .param("northEastLat", NORTH_EAST_LATITUDE)
                .param("northEastLng", NORTH_EAST_LONGITUDE);
    }

    private void assertNoInternalDetails(String body) {
        assertFalse(body.contains("SQL"));
        assertFalse(body.contains("Exception"));
        assertFalse(body.contains("java."));
        assertFalse(body.contains("org.springframework"));
        assertFalse(body.contains("com.toadzip"));
        assertFalse(body.contains("Failed to convert"));
        assertFalse(body.contains("For input string"));
        assertFalse(body.contains("stackTrace"));
        assertFalse(body.contains("\"stack\""));
        assertFalse(body.contains("\"stackTrace\""));
        assertFalse(body.contains("\"cause\""));
        assertFalse(body.contains("\"exception\""));
        assertFalse(body.contains("\"exceptionType\""));
        assertFalse(body.contains("\"type\""));
        assertFalse(body.contains("\"class\""));
    }

    private static List<Arguments> invalidRequestCases() {
        return List.of(
                errorCase("공백 keyword", null, true, "keyword", "   "),
                errorCase("음수 금액", null, true, "minDeposit", "-1"),
                errorCase("음수 면적", null, true, "minExclusiveArea", "-0.01"),
                errorCase("역전 금액 범위", null, true, "minDeposit", "2", "maxDeposit", "1"),
                errorCase(
                        "역전 월 임대료 범위",
                        null,
                        true,
                        "minMonthlyRent",
                        "2",
                        "maxMonthlyRent",
                        "1"
                ),
                errorCase("역전 면적 범위", null, true, "minExclusiveArea", "2", "maxExclusiveArea", "1"),
                errorCase("범위 밖 year", null, true, "builtYearFrom", "0"),
                errorCase("역전 year 범위", null, true, "builtYearFrom", "2021", "builtYearTo", "2020"),
                errorCase("CANCELLED 신청 상태", null, true, "applicationStatuses", "CANCELLED"),
                errorCase("빈 enum 요소", null, true, "agencyCodes", "LH", "agencyCodes", ""),
                errorCase("filter가 cursor보다 우선", null, true, "keyword", " ", "cursor", "bad!")
        );
    }

    private static List<Arguments> invalidRegionCases() {
        return List.of(
                errorCase("공백 regionCode", null, true, "regionCode", " "),
                errorCase("형식 오류 regionCode", null, true, "regionCode", "111"),
                errorCase("미등록 2자리 regionCode", null, true, "regionCode", "99"),
                errorCase("미등록 5자리 regionCode", null, true, "regionCode", "99999"),
                errorCase("region이 cursor보다 우선", null, true, "regionCode", "99999", "cursor", "bad!")
        );
    }

    private static List<Arguments> malformedBindingCases() {
        return List.of(
                errorCase("malformed enum", "agencyCodes", true, "agencyCodes", "UNKNOWN"),
                errorCase("malformed number", "minDeposit", true, "minDeposit", "not-a-number"),
                errorCase("malformed decimal", "minExclusiveArea", true, "minExclusiveArea", "not-a-decimal"),
                errorCase("malformed boolean", "hasElevator", true, "hasElevator", "not-a-boolean")
        );
    }

    private static List<Arguments> invalidCursorCases() {
        return List.of(
                errorCase("malformed cursor", null, true, "cursor", "bad!"),
                errorCase(
                        "typed-value cursor",
                        null,
                        true,
                        "sort",
                        "DEPOSIT_ASC",
                        "cursor",
                        encodedCursor("v2|DEPOSIT_ASC|0|not-a-decimal|1")
                ),
                errorCase(
                        "sort mismatch cursor",
                        null,
                        true,
                        "sort",
                        "DEPOSIT_ASC",
                        "cursor",
                        encodedCursor("v2|LATEST_ANNOUNCEMENT|0|2026-08-27|1")
                )
        );
    }

    private static List<Arguments> invalidBoundsCases() {
        return List.of(
                errorCase(
                        "누락 bounds",
                        null,
                        false,
                        "southWestLng",
                        SOUTH_WEST_LONGITUDE,
                        "northEastLat",
                        NORTH_EAST_LATITUDE,
                        "northEastLng",
                        NORTH_EAST_LONGITUDE
                ),
                errorCase(
                        "범위초과 bounds",
                        null,
                        false,
                        "southWestLat",
                        "-91",
                        "southWestLng",
                        SOUTH_WEST_LONGITUDE,
                        "northEastLat",
                        NORTH_EAST_LATITUDE,
                        "northEastLng",
                        NORTH_EAST_LONGITUDE
                ),
                errorCase(
                        "역전 bounds",
                        null,
                        false,
                        "southWestLat",
                        NORTH_EAST_LATITUDE,
                        "southWestLng",
                        SOUTH_WEST_LONGITUDE,
                        "northEastLat",
                        SOUTH_WEST_LATITUDE,
                        "northEastLng",
                        NORTH_EAST_LONGITUDE
                )
        );
    }

    private static Arguments errorCase(
            String name,
            String expectedField,
            boolean addDefaultBounds,
            String... parameterPairs
    ) {
        return Arguments.of(new HttpErrorCase(
                name,
                expectedField,
                addDefaultBounds,
                List.of(parameterPairs)
        ));
    }

    private static String encodedCursor(String payload) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private record HttpErrorCase(
            String name,
            String expectedField,
            boolean addDefaultBounds,
            List<String> parameterPairs
    ) {
        @Override
        public String toString() {
            return name;
        }
    }

    private enum FullFilterSentinel {
        KEYWORD,
        REGION,
        RENTAL_TYPE,
        APPLICATION_STATUS,
        AGENCY,
        RECRUITMENT_TYPE,
        MIN_DEPOSIT,
        MAX_DEPOSIT,
        MIN_MONTHLY_RENT,
        MAX_MONTHLY_RENT,
        MIN_EXCLUSIVE_AREA,
        MAX_EXCLUSIVE_AREA,
        BUILT_YEAR_FROM,
        BUILT_YEAR_TO,
        ELEVATOR,
        SOUTH_WEST_LATITUDE,
        NORTH_EAST_LATITUDE,
        SOUTH_WEST_LONGITUDE,
        NORTH_EAST_LONGITUDE
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
        return persistComplex(
                name,
                latitude,
                longitude,
                "행복주택",
                "LH",
                LocalDate.of(2020, 1, 1),
                true,
                "11",
                "11140",
                "서울특별시 중구 세종대로 110"
        );
    }

    private HousingComplex persistComplex(
            String name,
            String latitude,
            String longitude,
            String supplyType,
            String provider,
            LocalDate completionDate,
            boolean elevatorInstalled,
            String provinceCode,
            String districtCode,
            String roadAddress
    ) {
        HousingComplex complex = HousingComplex.create(
                name,
                "source-" + name,
                supplyType,
                Address.create(
                        roadAddress,
                        districtCode + "101001000100000",
                        districtCode + "10100",
                        provinceCode,
                        districtCode,
                        new BigDecimal(latitude),
                        new BigDecimal(longitude)
                ),
                100,
                provider,
                completionDate,
                "개별난방",
                "아파트",
                "계단식",
                elevatorInstalled,
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
            boolean duplex,
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
                null,
                "행복주택",
                "신규모집",
                "LH"
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
        return persistAnnouncement(
                previous,
                status,
                suffix,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                actualCompetitionRate,
                "행복주택",
                "신규모집",
                "LH"
        );
    }

    private Announcement persistAnnouncement(
            Announcement previous,
            String status,
            String suffix,
            LocalDate postedDate,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            BigDecimal actualCompetitionRate,
            String supplyType,
            String recruitmentType,
            String provider
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
                supplyType,
                recruitmentType,
                provider,
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
