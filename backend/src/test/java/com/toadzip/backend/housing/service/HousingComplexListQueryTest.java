package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.HousingComplexListItemResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexListResponse;
import com.toadzip.backend.housing.exception.InvalidComplexCursorException;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.exception.InvalidMapBoundsException;
import com.toadzip.backend.housing.exception.InvalidRegionCodeException;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor;
import com.toadzip.backend.housing.repository.ComplexSummaryQueryRepository;
import com.toadzip.backend.housing.repository.ComplexSummaryRow;
import com.toadzip.backend.housing.repository.HousingComplexSearchCondition;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class HousingComplexListQueryTest {

    private static final MapBounds BOUNDS = MapBounds.of(
            new BigDecimal("37.400000"),
            new BigDecimal("126.800000"),
            new BigDecimal("37.600000"),
            new BigDecimal("127.100000")
    );

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-26T15:30:00Z"),
            ZoneOffset.UTC
    );

    private ComplexSummaryQueryRepository repository;

    private RegionCodeResolver regionCodeResolver;

    private HousingComplexQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ComplexSummaryQueryRepository.class);
        regionCodeResolver = mock(RegionCodeResolver.class);
        when(regionCodeResolver.resolve("11", "11140")).thenReturn(Optional.of("서울특별시 중구"));
        when(regionCodeResolver.isRegisteredProvinceCode("11")).thenReturn(true);
        when(regionCodeResolver.equivalentCodes("12210"))
                .thenReturn(Optional.of(Set.of("12210", "29110")));
        when(regionCodeResolver.equivalentCodes("29110"))
                .thenReturn(Optional.of(Set.of("12210", "29110")));
        HousingComplexSummaryMapper summaryMapper = new HousingComplexSummaryMapper(
                new HousingComplexCodeMapper(),
                regionCodeResolver
        );
        service = new HousingComplexQueryService(repository, summaryMapper, regionCodeResolver, CLOCK);
    }

    @Test
    void 요청을_정규화해_서울_오늘을_포함한_검색조건으로_size보다_하나_더_조회한다() {
        when(repository.findPage(
                any(HousingComplexSearchCondition.class),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                isNull(),
                eq(21)
        )).thenReturn(List.of());

        service.getComplexes(fullSearchRequest("11"), ComplexSort.LATEST_ANNOUNCEMENT, null, 20);

        ArgumentCaptor<HousingComplexSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(HousingComplexSearchCondition.class);
        verify(repository).findPage(
                conditionCaptor.capture(),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                isNull(),
                eq(21)
        );
        HousingComplexSearchCondition condition = conditionCaptor.getValue();
        assertAll(
                () -> assertEquals(BOUNDS, condition.bounds()),
                () -> assertEquals("행복 단지", condition.keyword()),
                () -> assertEquals("11", condition.provinceCode()),
                () -> assertEquals(Set.of(), condition.cityCountyDistrictCodes()),
                () -> assertEquals(Set.of(RentalType.HAPPY_HOUSING, RentalType.NATIONAL_RENTAL),
                        condition.rentalTypes()),
                () -> assertEquals(Set.of(ApplicationStatus.APPLYING, ApplicationStatus.CLOSED),
                        condition.applicationStatuses()),
                () -> assertEquals(Set.of(AgencyCode.LH, AgencyCode.SH), condition.agencyCodes()),
                () -> assertEquals(Set.of(RecruitmentType.NEW, RecruitmentType.WAITLIST),
                        condition.recruitmentTypes()),
                () -> assertEquals(new BigDecimal("10000000"), condition.minDeposit()),
                () -> assertEquals(new BigDecimal("70000000"), condition.maxDeposit()),
                () -> assertEquals(new BigDecimal("100000"), condition.minMonthlyRent()),
                () -> assertEquals(new BigDecimal("300000"), condition.maxMonthlyRent()),
                () -> assertEquals(new BigDecimal("36.12"), condition.minExclusiveArea()),
                () -> assertEquals(new BigDecimal("44.87"), condition.maxExclusiveArea()),
                () -> assertEquals(2018, condition.builtYearFrom()),
                () -> assertEquals(2026, condition.builtYearTo()),
                () -> assertEquals(true, condition.hasElevator()),
                () -> assertEquals(LocalDate.of(2026, 8, 27), condition.today()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> condition.rentalTypes().add(RentalType.ETC))
        );
    }

    @Test
    void 다섯자리_지역은_동등한_시군구_코드_집합으로_정규화한다() {
        when(repository.findPage(any(), any(), any(), eq(21))).thenReturn(List.of());

        service.getComplexes(fullSearchRequest("12210"), null, null, 20);

        ArgumentCaptor<HousingComplexSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(HousingComplexSearchCondition.class);
        verify(repository).findPage(
                conditionCaptor.capture(),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                isNull(),
                eq(21)
        );
        assertAll(
                () -> assertNull(conditionCaptor.getValue().provinceCode()),
                () -> assertEquals(Set.of("12210", "29110"),
                        conditionCaptor.getValue().cityCountyDistrictCodes())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSearchRequests")
    void 의미가_잘못된_검색요청은_repository_호출_전에_거부한다(
            String reason,
            HousingComplexSearchRequest request
    ) {
        assertThrows(
                InvalidComplexRequestException.class,
                () -> service.getComplexes(request, ComplexSort.LATEST_ANNOUNCEMENT, null, 20)
        );

        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 51})
    void 최종_목록_API의_size가_범위를_벗어나면_repository_호출_전에_거부한다(int size) {
        assertThrows(
                InvalidComplexRequestException.class,
                () -> service.getComplexes(baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, null, size)
        );

        verifyNoInteractions(repository);
    }

    @Test
    void 검색조건이_잘못되면_커서보다_먼저_검색요청_오류를_반환한다() {
        HousingComplexSearchRequest request = requestWithKeyword("   ");

        assertThrows(
                InvalidComplexRequestException.class,
                () -> service.getComplexes(request, ComplexSort.LATEST_ANNOUNCEMENT, "bad-cursor", 20)
        );

        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRegionRequests")
    void 잘못된_지역은_repository_호출_전에_거부한다(String reason, HousingComplexSearchRequest request) {
        assertThrows(
                InvalidRegionCodeException.class,
                () -> service.getComplexes(request, ComplexSort.LATEST_ANNOUNCEMENT, null, 20)
        );

        verifyNoInteractions(repository);
    }

    @Test
    void 잘못된_지역은_커서보다_먼저_지역_오류를_반환한다() {
        assertThrows(
                InvalidRegionCodeException.class,
                () -> service.getComplexes(
                        requestWithRegion("99999"),
                        ComplexSort.LATEST_ANNOUNCEMENT,
                        "bad-cursor",
                        20
                )
        );

        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBoundsRequests")
    void 잘못된_bounds는_repository_호출_전에_거부한다(String reason, HousingComplexSearchRequest request) {
        assertThrows(
                InvalidMapBoundsException.class,
                () -> service.getComplexes(request, ComplexSort.LATEST_ANNOUNCEMENT, null, 20)
        );

        verifyNoInteractions(repository);
    }

    @Test
    void size보다_하나_더_조회해_다음_페이지_커서를_만든다() {
        when(repository.findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), isNull(), eq(3))).thenReturn(List.of(
                row(10L, LocalDate.of(2026, 8, 21), "ORIGINAL", LocalDate.of(2026, 8, 22),
                        LocalDate.of(2026, 8, 29)),
                row(9L, LocalDate.of(2026, 8, 20), "ORIGINAL", LocalDate.of(2026, 8, 22),
                        LocalDate.of(2026, 8, 29)),
                row(8L, LocalDate.of(2026, 8, 19), "ORIGINAL", LocalDate.of(2026, 8, 22),
                        LocalDate.of(2026, 8, 29))
        ));

        HousingComplexListResponse response = service.getComplexes(
                baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, null, 2);

        assertAll(
                () -> assertEquals(2, response.items().size()),
                () -> assertEquals(List.of(10L, 9L), ids(response)),
                () -> assertTrue(response.hasNext()),
                () -> assertNotNull(response.nextCursor()),
                () -> assertEquals(
                        new ComplexSummaryCursor(
                                ComplexSort.LATEST_ANNOUNCEMENT,
                                new ComplexSummaryCursor.DateValue(LocalDate.of(2026, 8, 20)),
                                9L
                        ),
                        new HousingComplexCursorCodec().decode(
                                response.nextCursor(), ComplexSort.LATEST_ANNOUNCEMENT)
                )
        );
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ComplexSort.class)
    void 다섯_정렬은_반환한_페이지의_마지막_row값으로_typed_다음_커서를_만든다(
            ComplexSort sort
    ) {
        when(repository.findPage(any(), eq(sort), isNull(), eq(3))).thenReturn(List.of(
                row(10L, LocalDate.of(2026, 8, 21), "ORIGINAL", LocalDate.of(2026, 8, 22),
                        LocalDate.of(2026, 8, 29)),
                row(9L, LocalDate.of(2026, 8, 20), "ORIGINAL", LocalDate.of(2026, 8, 22),
                        LocalDate.of(2026, 8, 29)),
                row(8L, LocalDate.of(2026, 8, 19), "ORIGINAL", LocalDate.of(2026, 8, 22),
                        LocalDate.of(2026, 8, 29))
        ));

        HousingComplexListResponse response = service.getComplexes(baseSearchRequest(), sort, null, 2);

        assertEquals(expectedCursor(sort), new HousingComplexCursorCodec().decode(response.nextCursor(), sort));
    }

    @Test
    void v1_커서는_default_최신공고_정렬에서_해석해_repository에_전달한다() {
        ComplexSummaryCursor decoded = new ComplexSummaryCursor(
                ComplexSort.LATEST_ANNOUNCEMENT,
                new ComplexSummaryCursor.DateValue(LocalDate.of(2026, 8, 20)),
                9L
        );
        when(repository.findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), eq(decoded), eq(2)))
                .thenReturn(List.of());

        service.getComplexes(baseSearchRequest(), null, legacyCursor("2026-08-20", 9L), 1);

        verify(repository).findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), eq(decoded), eq(2));
    }

    @Test
    void v1_커서와_최신공고가_아닌_정렬의_조합은_repository_호출_전에_거부한다() {
        String cursor = legacyCursor("2026-08-20", 9L);

        assertThrows(
                InvalidComplexCursorException.class,
                () -> service.getComplexes(baseSearchRequest(), ComplexSort.DEPOSIT_ASC, cursor, 1)
        );

        verifyNoInteractions(repository);
    }

    @Test
    void v2_커서와_요청_정렬이_다르면_repository_호출_전에_거부한다() {
        String cursor = new HousingComplexCursorCodec().encode(new ComplexSummaryCursor(
                ComplexSort.DEPOSIT_ASC,
                new ComplexSummaryCursor.DecimalValue(new BigDecimal("50000000")),
                9L
        ));

        assertThrows(
                InvalidComplexCursorException.class,
                () -> service.getComplexes(baseSearchRequest(), ComplexSort.AREA_DESC, cursor, 1)
        );

        verifyNoInteractions(repository);
    }

    @Test
    void null_게시일_커서에서도_반환한_페이지의_마지막_단지로_다음_커서를_만든다() {
        ComplexSummaryCursor cursor = new ComplexSummaryCursor(ComplexSort.LATEST_ANNOUNCEMENT, null, 40L);
        when(repository.findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), eq(cursor), eq(2))).thenReturn(List.of(
                rowWithoutAnnouncement(30L),
                rowWithoutAnnouncement(20L)
        ));

        String encodedCursor = new HousingComplexCursorCodec().encode(cursor);
        HousingComplexListResponse response = service.getComplexes(
                baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, encodedCursor, 1);

        assertAll(
                () -> assertEquals(List.of(30L), ids(response)),
                () -> assertTrue(response.hasNext()),
                () -> assertEquals(
                        new ComplexSummaryCursor(ComplexSort.LATEST_ANNOUNCEMENT, null, 30L),
                        new HousingComplexCursorCodec().decode(
                                response.nextCursor(), ComplexSort.LATEST_ANNOUNCEMENT)
                )
        );
    }

    @Test
    void 추가_행이_없으면_다음_커서를_반환하지_않는다() {
        when(repository.findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), isNull(), eq(3)))
                .thenReturn(List.of(rowWithoutAnnouncement(10L)));

        HousingComplexListResponse response = service.getComplexes(
                baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, null, 2);

        assertAll(
                () -> assertEquals(List.of(10L), ids(response)),
                () -> assertFalse(response.hasNext()),
                () -> assertNull(response.nextCursor())
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 51})
    void size가_1부터_50_사이가_아니면_요청을_거부한다(int size) {
        assertThrows(
                InvalidComplexRequestException.class,
                () -> service.getComplexes(baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, null, size)
        );
    }

    @Test
    void 단지와_대표공고_요약을_공개_목록_계약으로_변환한다() {
        when(repository.findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), isNull(), eq(2))).thenReturn(List.of(row(
                17L,
                LocalDate.of(2026, 8, 20),
                "ORIGINAL",
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 30)
        )));

        HousingComplexListResponse response = service.getComplexes(
                baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, null, 1);
        assertEquals(1, response.items().size());
        HousingComplexListItemResponse item = response.items().getFirst();

        assertAll(
                () -> assertEquals(17L, item.complexId()),
                () -> assertEquals("https://example.com/thumbnail.png", item.thumbnailImageUrl()),
                () -> assertEquals("서울특별시 중구", item.regionName()),
                () -> assertEquals("행복 단지 17", item.name()),
                () -> assertEquals("HAPPY_HOUSING", item.rentalType()),
                () -> assertEquals("LH", item.agency().code()),
                () -> assertEquals("한국토지주택공사", item.agency().name()),
                () -> assertEquals(new BigDecimal("36.12"), item.exclusiveAreaMin()),
                () -> assertEquals(new BigDecimal("44.87"), item.exclusiveAreaMax()),
                () -> assertEquals(50000000L, item.depositMin()),
                () -> assertEquals(70000000L, item.depositMax()),
                () -> assertEquals(200000L, item.monthlyRentMin()),
                () -> assertEquals(300000L, item.monthlyRentMax()),
                () -> assertEquals(117L, item.representativeAnnouncement().announcementId()),
                () -> assertEquals("ORIGINAL", item.representativeAnnouncement().publicationType()),
                () -> assertEquals("BEFORE_APPLICATION", item.representativeAnnouncement().applicationStatus()),
                () -> assertEquals(LocalDate.of(2026, 8, 30),
                        item.representativeAnnouncement().applicationEndAt()),
                () -> assertEquals(3, item.representativeAnnouncement().dDay())
        );
    }

    @ParameterizedTest
    @CsvSource({
            "ORIGINAL, ORIGINAL",
            "원공고, ORIGINAL",
            "CORRECTION, CORRECTION",
            "정정공고, CORRECTION"
    })
    void canonical과_legacy_공고구분을_canonical_코드로_반환한다(
            String storedValue,
            String expectedCode
    ) {
        when(repository.findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), isNull(), eq(2))).thenReturn(List.of(row(
                1L,
                LocalDate.of(2026, 8, 20),
                storedValue,
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 30)
        )));

        HousingComplexListResponse response = service.getComplexes(
                baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, null, 1);
        assertEquals(1, response.items().size());

        assertEquals(expectedCode, response.items().getFirst().representativeAnnouncement().publicationType());
    }

    @Test
    void 자정을_지나는_목록_요청도_검색조건의_기준일로_응답상태와_D_Day를_계산한다() {
        Instant beforeSeoulMidnight = Instant.parse("2026-08-27T14:59:59Z");
        Instant afterSeoulMidnight = Instant.parse("2026-08-27T15:00:01Z");
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenReturn(beforeSeoulMidnight, afterSeoulMidnight);
        HousingComplexSummaryMapper summaryMapper = new HousingComplexSummaryMapper(
                new HousingComplexCodeMapper(),
                regionCodeResolver
        );
        service = new HousingComplexQueryService(repository, summaryMapper, regionCodeResolver, advancingClock);
        when(repository.findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), isNull(), eq(2))).thenReturn(List.of(row(
                1L,
                LocalDate.of(2026, 8, 20),
                "ORIGINAL",
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 27)
        )));

        HousingComplexListResponse response = service.getComplexes(
                baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, null, 1);

        ArgumentCaptor<HousingComplexSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(HousingComplexSearchCondition.class);
        verify(repository).findPage(
                conditionCaptor.capture(),
                eq(ComplexSort.LATEST_ANNOUNCEMENT),
                isNull(),
                eq(2)
        );
        HousingComplexListItemResponse item = response.items().getFirst();
        assertAll(
                () -> assertEquals(LocalDate.of(2026, 8, 27), conditionCaptor.getValue().today()),
                () -> assertEquals("APPLYING", item.representativeAnnouncement().applicationStatus()),
                () -> assertEquals(0, item.representativeAnnouncement().dDay())
        );
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "2026-08-28, 2026-08-30, BEFORE_APPLICATION, 3",
            "2026-08-20, 2026-08-27, APPLYING, 0",
            "2026-08-20, 2026-08-26, CLOSED, NULL"
    })
    void 서울_오늘과_접수기간으로_접수상태와_D_Day를_계산한다(
            LocalDate applicationStartDate,
            LocalDate applicationEndDate,
            String expectedStatus,
            Integer expectedDDay
    ) {
        when(repository.findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), isNull(), eq(2))).thenReturn(List.of(row(
                1L,
                LocalDate.of(2026, 8, 20),
                "ORIGINAL",
                applicationStartDate,
                applicationEndDate
        )));

        HousingComplexListResponse response = service.getComplexes(
                baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, null, 1);
        assertEquals(1, response.items().size());

        assertAll(
                () -> assertEquals(
                        expectedStatus,
                        response.items().getFirst().representativeAnnouncement().applicationStatus()
                ),
                () -> assertEquals(
                        expectedDDay,
                        response.items().getFirst().representativeAnnouncement().dDay()
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLATION", "UNKNOWN_PUBLICATION"})
    void 응답으로_지원하지_않는_공고구분_저장값을_거부한다(String storedValue) {
        when(repository.findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), isNull(), eq(2))).thenReturn(List.of(row(
                1L,
                LocalDate.of(2026, 8, 20),
                storedValue,
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 30)
        )));

        assertThrows(IllegalStateException.class, () -> service.getComplexes(
                baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, null, 1));
    }

    @Test
    void 대표공고가_없는_단지는_대표공고를_null로_반환한다() {
        when(repository.findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), isNull(), eq(2)))
                .thenReturn(List.of(rowWithoutAnnouncement(1L)));

        HousingComplexListResponse response = service.getComplexes(
                baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, null, 1);
        assertEquals(1, response.items().size());

        assertNull(response.items().getFirst().representativeAnnouncement());
    }

    @Test
    void 저장된_지역코드를_해석할_수_없으면_데이터_무결성_실패로_처리한다() {
        ComplexSummaryRow unresolved = new ComplexSummaryRow(
                1L,
                "해석할 수 없는 지역 단지",
                null,
                "99",
                "99999",
                "HAPPY_HOUSING",
                "LH",
                new BigDecimal("37.500000"),
                new BigDecimal("126.900000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2020, 1, 1)
        );
        when(repository.findPage(any(), eq(ComplexSort.LATEST_ANNOUNCEMENT), isNull(), eq(2)))
                .thenReturn(List.of(unresolved));

        assertThrows(IllegalStateException.class, () -> service.getComplexes(
                baseSearchRequest(), ComplexSort.LATEST_ANNOUNCEMENT, null, 1));
    }

    private static Stream<Arguments> invalidSearchRequests() {
        return Stream.of(
                Arguments.of("request null", null),
                Arguments.of("blank keyword", requestWithKeyword("   ")),
                Arguments.of("negative deposit", requestWithAmounts(-1L, null, null, null, null, null)),
                Arguments.of("negative monthly rent", requestWithAmounts(null, null, -1L, null, null, null)),
                Arguments.of("negative exclusive area",
                        requestWithAmounts(null, null, null, null, new BigDecimal("-0.01"), null)),
                Arguments.of("deposit minimum greater than maximum",
                        requestWithAmounts(2L, 1L, null, null, null, null)),
                Arguments.of("monthly rent minimum greater than maximum",
                        requestWithAmounts(null, null, 2L, 1L, null, null)),
                Arguments.of("area minimum greater than maximum",
                        requestWithAmounts(null, null, null, null, new BigDecimal("2"), new BigDecimal("1"))),
                Arguments.of("year below lower bound", requestWithYears(0, null)),
                Arguments.of("year above upper bound", requestWithYears(null, 10_000)),
                Arguments.of("year from greater than to", requestWithYears(2027, 2026)),
                Arguments.of("rental type null element", requestWithLists(
                        java.util.Arrays.asList(RentalType.HAPPY_HOUSING, null), null, null, null)),
                Arguments.of("application status null element", requestWithLists(
                        null, java.util.Arrays.asList(ApplicationStatus.APPLYING, null), null, null)),
                Arguments.of("agency null element", requestWithLists(
                        null, null, java.util.Arrays.asList(AgencyCode.LH, null), null)),
                Arguments.of("recruitment type null element", requestWithLists(
                        null, null, null, java.util.Arrays.asList(RecruitmentType.NEW, null))),
                Arguments.of("cancelled status", requestWithLists(
                        null, List.of(ApplicationStatus.CANCELLED), null, null))
        );
    }

    private static Stream<Arguments> invalidRegionRequests() {
        return Stream.of(
                Arguments.of("blank", requestWithRegion("   ")),
                Arguments.of("malformed", requestWithRegion("111")),
                Arguments.of("unregistered province", requestWithRegion("99")),
                Arguments.of("unresolved district", requestWithRegion("99999"))
        );
    }

    private static Stream<Arguments> invalidBoundsRequests() {
        return Stream.of(
                Arguments.of("missing", requestWithBounds(null, "126.8", "37.6", "127.1")),
                Arguments.of("out of range", requestWithBounds("-91", "126.8", "37.6", "127.1")),
                Arguments.of("reversed", requestWithBounds("37.6", "126.8", "37.4", "127.1"))
        );
    }

    private static HousingComplexSearchRequest baseSearchRequest() {
        return request(
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null,
                new BigDecimal("37.400000"), new BigDecimal("126.800000"),
                new BigDecimal("37.600000"), new BigDecimal("127.100000")
        );
    }

    private static HousingComplexSearchRequest fullSearchRequest(String regionCode) {
        return request(
                " 행복 단지 ", regionCode,
                List.of(RentalType.HAPPY_HOUSING, RentalType.NATIONAL_RENTAL),
                List.of(ApplicationStatus.APPLYING, ApplicationStatus.CLOSED),
                List.of(AgencyCode.LH, AgencyCode.SH),
                List.of(RecruitmentType.NEW, RecruitmentType.WAITLIST),
                10_000_000L, 70_000_000L, 100_000L, 300_000L,
                new BigDecimal("36.12"), new BigDecimal("44.87"),
                2018, 2026, true,
                new BigDecimal("37.400000"), new BigDecimal("126.800000"),
                new BigDecimal("37.600000"), new BigDecimal("127.100000")
        );
    }

    private static HousingComplexSearchRequest requestWithKeyword(String keyword) {
        HousingComplexSearchRequest base = baseSearchRequest();
        return request(
                keyword, base.regionCode(), base.rentalTypes(), base.applicationStatuses(),
                base.agencyCodes(), base.recruitmentTypes(), base.minDeposit(), base.maxDeposit(),
                base.minMonthlyRent(), base.maxMonthlyRent(), base.minExclusiveArea(), base.maxExclusiveArea(),
                base.builtYearFrom(), base.builtYearTo(), base.hasElevator(),
                base.southWestLat(), base.southWestLng(), base.northEastLat(), base.northEastLng()
        );
    }

    private static HousingComplexSearchRequest requestWithRegion(String regionCode) {
        HousingComplexSearchRequest base = baseSearchRequest();
        return request(
                base.keyword(), regionCode, base.rentalTypes(), base.applicationStatuses(),
                base.agencyCodes(), base.recruitmentTypes(), base.minDeposit(), base.maxDeposit(),
                base.minMonthlyRent(), base.maxMonthlyRent(), base.minExclusiveArea(), base.maxExclusiveArea(),
                base.builtYearFrom(), base.builtYearTo(), base.hasElevator(),
                base.southWestLat(), base.southWestLng(), base.northEastLat(), base.northEastLng()
        );
    }

    private static HousingComplexSearchRequest requestWithAmounts(
            Long minDeposit,
            Long maxDeposit,
            Long minMonthlyRent,
            Long maxMonthlyRent,
            BigDecimal minExclusiveArea,
            BigDecimal maxExclusiveArea
    ) {
        HousingComplexSearchRequest base = baseSearchRequest();
        return request(
                base.keyword(), base.regionCode(), base.rentalTypes(), base.applicationStatuses(),
                base.agencyCodes(), base.recruitmentTypes(), minDeposit, maxDeposit,
                minMonthlyRent, maxMonthlyRent, minExclusiveArea, maxExclusiveArea,
                base.builtYearFrom(), base.builtYearTo(), base.hasElevator(),
                base.southWestLat(), base.southWestLng(), base.northEastLat(), base.northEastLng()
        );
    }

    private static HousingComplexSearchRequest requestWithYears(Integer builtYearFrom, Integer builtYearTo) {
        HousingComplexSearchRequest base = baseSearchRequest();
        return request(
                base.keyword(), base.regionCode(), base.rentalTypes(), base.applicationStatuses(),
                base.agencyCodes(), base.recruitmentTypes(), base.minDeposit(), base.maxDeposit(),
                base.minMonthlyRent(), base.maxMonthlyRent(), base.minExclusiveArea(), base.maxExclusiveArea(),
                builtYearFrom, builtYearTo, base.hasElevator(),
                base.southWestLat(), base.southWestLng(), base.northEastLat(), base.northEastLng()
        );
    }

    private static HousingComplexSearchRequest requestWithLists(
            List<RentalType> rentalTypes,
            List<ApplicationStatus> applicationStatuses,
            List<AgencyCode> agencyCodes,
            List<RecruitmentType> recruitmentTypes
    ) {
        HousingComplexSearchRequest base = baseSearchRequest();
        return request(
                base.keyword(), base.regionCode(), rentalTypes, applicationStatuses, agencyCodes, recruitmentTypes,
                base.minDeposit(), base.maxDeposit(), base.minMonthlyRent(), base.maxMonthlyRent(),
                base.minExclusiveArea(), base.maxExclusiveArea(), base.builtYearFrom(), base.builtYearTo(),
                base.hasElevator(), base.southWestLat(), base.southWestLng(), base.northEastLat(), base.northEastLng()
        );
    }

    private static HousingComplexSearchRequest requestWithBounds(
            String southWestLat,
            String southWestLng,
            String northEastLat,
            String northEastLng
    ) {
        HousingComplexSearchRequest base = baseSearchRequest();
        return request(
                base.keyword(), base.regionCode(), base.rentalTypes(), base.applicationStatuses(),
                base.agencyCodes(), base.recruitmentTypes(), base.minDeposit(), base.maxDeposit(),
                base.minMonthlyRent(), base.maxMonthlyRent(), base.minExclusiveArea(), base.maxExclusiveArea(),
                base.builtYearFrom(), base.builtYearTo(), base.hasElevator(),
                decimal(southWestLat), decimal(southWestLng), decimal(northEastLat), decimal(northEastLng)
        );
    }

    private static BigDecimal decimal(String value) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(value);
    }

    private static HousingComplexSearchRequest request(
            String keyword,
            String regionCode,
            List<RentalType> rentalTypes,
            List<ApplicationStatus> applicationStatuses,
            List<AgencyCode> agencyCodes,
            List<RecruitmentType> recruitmentTypes,
            Long minDeposit,
            Long maxDeposit,
            Long minMonthlyRent,
            Long maxMonthlyRent,
            BigDecimal minExclusiveArea,
            BigDecimal maxExclusiveArea,
            Integer builtYearFrom,
            Integer builtYearTo,
            Boolean hasElevator,
            BigDecimal southWestLat,
            BigDecimal southWestLng,
            BigDecimal northEastLat,
            BigDecimal northEastLng
    ) {
        return new HousingComplexSearchRequest(
                keyword, regionCode, rentalTypes, applicationStatuses, agencyCodes, recruitmentTypes,
                minDeposit, maxDeposit, minMonthlyRent, maxMonthlyRent,
                minExclusiveArea, maxExclusiveArea, builtYearFrom, builtYearTo, hasElevator,
                southWestLat, southWestLng, northEastLat, northEastLng
        );
    }

    private ComplexSummaryRow row(
            long complexId,
            LocalDate postedDate,
            String publicationType,
            LocalDate applicationStartDate,
            LocalDate applicationEndDate
    ) {
        return new ComplexSummaryRow(
                complexId,
                "행복 단지 " + complexId,
                "https://example.com/thumbnail.png",
                "11",
                "11140",
                "HAPPY_HOUSING",
                "LH",
                new BigDecimal("37.500000"),
                new BigDecimal("126.900000"),
                new BigDecimal("36.12"),
                new BigDecimal("44.87"),
                new BigDecimal("50000000"),
                new BigDecimal("70000000"),
                new BigDecimal("200000"),
                new BigDecimal("300000"),
                complexId + 100,
                publicationType,
                postedDate,
                applicationStartDate,
                applicationEndDate,
                LocalDate.of(2020, 1, 1)
        );
    }

    private ComplexSummaryRow rowWithoutAnnouncement(long complexId) {
        return new ComplexSummaryRow(
                complexId,
                "공고 없는 단지 " + complexId,
                null,
                "11",
                "11140",
                "NATIONAL_RENTAL",
                "SH",
                new BigDecimal("37.500000"),
                new BigDecimal("126.900000"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2020, 1, 1)
        );
    }

    private ComplexSummaryCursor expectedCursor(ComplexSort sort) {
        ComplexSummaryCursor.SortValue primaryValue = switch (sort) {
            case LATEST_ANNOUNCEMENT -> new ComplexSummaryCursor.DateValue(LocalDate.of(2026, 8, 20));
            case DEPOSIT_ASC -> new ComplexSummaryCursor.DecimalValue(new BigDecimal("50000000"));
            case MONTHLY_RENT_ASC -> new ComplexSummaryCursor.DecimalValue(new BigDecimal("200000"));
            case AREA_DESC -> new ComplexSummaryCursor.DecimalValue(new BigDecimal("44.87"));
            case COMPLETION_DATE_DESC -> new ComplexSummaryCursor.DateValue(LocalDate.of(2020, 1, 1));
        };
        return new ComplexSummaryCursor(sort, primaryValue, 9L);
    }

    private String legacyCursor(String rawPostedDate, long complexId) {
        String payload = "v1|" + rawPostedDate + "|" + complexId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private List<Long> ids(HousingComplexListResponse response) {
        return response.items().stream().map(HousingComplexListItemResponse::complexId).toList();
    }
}
