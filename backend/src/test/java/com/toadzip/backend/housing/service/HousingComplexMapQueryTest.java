package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.HousingComplexMapItemResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexMapResponse;
import com.toadzip.backend.housing.repository.ComplexSummaryQueryRepository;
import com.toadzip.backend.housing.repository.ComplexSummaryRow;
import com.toadzip.backend.housing.repository.HousingComplexSearchCondition;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class HousingComplexMapQueryTest {

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

    private HousingComplexQueryService service;

    private RegionCodeResolver regionCodeResolver;

    @BeforeEach
    void setUp() {
        repository = mock(ComplexSummaryQueryRepository.class);
        regionCodeResolver = mock(RegionCodeResolver.class);
        when(regionCodeResolver.resolve("11", "11140")).thenReturn(Optional.of("서울특별시 중구"));
        when(regionCodeResolver.equivalentCodes("12210"))
                .thenReturn(Optional.of(Set.of("12210", "29110")));
        HousingComplexCodeMapper codeMapper = new HousingComplexCodeMapper();
        HousingComplexSummaryMapper summaryMapper = new HousingComplexSummaryMapper(codeMapper);
        service = new HousingComplexQueryService(repository, summaryMapper, regionCodeResolver, CLOCK);
    }

    @Test
    void 목록과_같은_정규화로_지도_검색조건을_조립해_repository에_전달한다() {
        when(repository.findAll(any(HousingComplexSearchCondition.class))).thenReturn(List.of());

        service.getComplexesForMap(fullSearchRequest());

        ArgumentCaptor<HousingComplexSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(HousingComplexSearchCondition.class);
        verify(repository).findAll(conditionCaptor.capture());
        HousingComplexSearchCondition condition = conditionCaptor.getValue();
        assertAll(
                () -> assertEquals(BOUNDS, condition.bounds()),
                () -> assertEquals("행복 단지", condition.keyword()),
                () -> assertNull(condition.provinceCode()),
                () -> assertEquals(Set.of("12210", "29110"), condition.cityCountyDistrictCodes()),
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
                () -> assertEquals(LocalDate.of(2026, 8, 27), condition.today())
        );
    }

    @Test
    void 지도_영역의_단지를_canonical_코드와_좌표로_반환한다() {
        when(repository.findAll(any(HousingComplexSearchCondition.class))).thenReturn(List.of(row(
                17L,
                "행복 단지",
                "HAPPY_HOUSING",
                "LH",
                "50000000",
                "70000000",
                "200000",
                "300000"
        )));

        HousingComplexMapResponse response = service.getComplexesForMap(baseSearchRequest());
        HousingComplexMapItemResponse item = response.items().getFirst();

        assertAll(
                () -> assertEquals(17L, item.complexId()),
                () -> assertEquals("행복 단지", item.name()),
                () -> assertEquals("HAPPY_HOUSING", item.rentalType()),
                () -> assertEquals("LH", item.agency().code()),
                () -> assertEquals("한국토지주택공사", item.agency().name()),
                () -> assertEquals(new BigDecimal("37.500000"), item.latitude()),
                () -> assertEquals(new BigDecimal("126.900000"), item.longitude()),
                () -> assertEquals(50000000L, item.depositMin()),
                () -> assertEquals(70000000L, item.depositMax()),
                () -> assertEquals(200000L, item.monthlyRentMin()),
                () -> assertEquals(300000L, item.monthlyRentMax())
        );
    }

    @ParameterizedTest
    @CsvSource({
            "HAPPY_HOUSING, HAPPY_HOUSING",
            "행복주택, HAPPY_HOUSING",
            "NATIONAL_RENTAL, NATIONAL_RENTAL",
            "국민임대, NATIONAL_RENTAL",
            "PERMANENT_RENTAL, PERMANENT_RENTAL",
            "영구임대, PERMANENT_RENTAL",
            "PUBLIC_RENTAL_50Y, PUBLIC_RENTAL_50Y",
            "50년공공임대, PUBLIC_RENTAL_50Y",
            "INTEGRATED_PUBLIC_RENTAL, INTEGRATED_PUBLIC_RENTAL",
            "통합공공임대, INTEGRATED_PUBLIC_RENTAL",
            "REDEVELOPMENT_RENTAL, REDEVELOPMENT_RENTAL",
            "재개발임대, REDEVELOPMENT_RENTAL",
            "ETC, ETC",
            "기타, ETC"
    })
    void canonical과_legacy_공급유형을_canonical_코드로_반환한다(String storedValue, String expectedCode) {
        when(repository.findAll(any(HousingComplexSearchCondition.class))).thenReturn(List.of(row(
                1L,
                "공급유형 단지",
                storedValue,
                "LH",
                null,
                null,
                null,
                null
        )));

        String rentalType = service.getComplexesForMap(baseSearchRequest()).items().getFirst().rentalType();

        assertEquals(expectedCode, rentalType);
    }

    @ParameterizedTest
    @CsvSource({
            "LH, LH, 한국토지주택공사",
            "한국토지주택공사, LH, 한국토지주택공사",
            "SH, SH, 서울주택도시공사",
            "서울주택도시공사, SH, 서울주택도시공사",
            "GH, GH, 경기주택도시공사",
            "경기주택도시공사, GH, 경기주택도시공사",
            "ETC, ETC, 기타",
            "기타, ETC, 기타"
    })
    void canonical과_legacy_공급기관을_고정된_코드와_이름으로_반환한다(
            String storedValue,
            String expectedCode,
            String expectedName
    ) {
        when(repository.findAll(any(HousingComplexSearchCondition.class))).thenReturn(List.of(row(
                1L,
                "공급기관 단지",
                "HAPPY_HOUSING",
                storedValue,
                null,
                null,
                null,
                null
        )));

        HousingComplexMapItemResponse item = service.getComplexesForMap(baseSearchRequest()).items().getFirst();

        assertAll(
                () -> assertEquals(expectedCode, item.agency().code()),
                () -> assertEquals(expectedName, item.agency().name())
        );
    }

    @Test
    void 알_수_없는_공급유형_저장값을_거부한다() {
        when(repository.findAll(any(HousingComplexSearchCondition.class))).thenReturn(List.of(row(
                1L,
                "알 수 없는 공급유형",
                "UNKNOWN_RENTAL",
                "LH",
                null,
                null,
                null,
                null
        )));

        assertThrows(IllegalStateException.class, () -> service.getComplexesForMap(baseSearchRequest()));
    }

    @Test
    void 알_수_없는_공급기관_저장값을_거부한다() {
        when(repository.findAll(any(HousingComplexSearchCondition.class))).thenReturn(List.of(row(
                1L,
                "알 수 없는 공급기관",
                "HAPPY_HOUSING",
                "UNKNOWN_AGENCY",
                null,
                null,
                null,
                null
        )));

        assertThrows(IllegalStateException.class, () -> service.getComplexesForMap(baseSearchRequest()));
    }

    @Test
    void 대표_공고가_없는_단지의_가격_범위를_null로_반환한다() {
        when(repository.findAll(any(HousingComplexSearchCondition.class))).thenReturn(List.of(row(
                1L,
                "공고 없는 단지",
                "HAPPY_HOUSING",
                "LH",
                null,
                null,
                null,
                null
        )));

        HousingComplexMapItemResponse item = service.getComplexesForMap(baseSearchRequest()).items().getFirst();

        assertAll(
                () -> assertNull(item.depositMin()),
                () -> assertNull(item.depositMax()),
                () -> assertNull(item.monthlyRentMin()),
                () -> assertNull(item.monthlyRentMax())
        );
    }

    @Test
    void repository_findAll이_반환한_단지_ID_오름차순을_그대로_보존한다() {
        when(repository.findAll(any(HousingComplexSearchCondition.class))).thenReturn(List.of(
                row(3L, "작은 ID 단지", "HAPPY_HOUSING", "LH", null, null, null, null),
                row(9L, "큰 ID 단지", "NATIONAL_RENTAL", "SH", null, null, null, null)
        ));

        List<Long> complexIds = service.getComplexesForMap(baseSearchRequest()).items().stream()
                .map(HousingComplexMapItemResponse::complexId)
                .toList();

        assertEquals(List.of(3L, 9L), complexIds);
    }

    @ParameterizedTest
    @ValueSource(strings = {"50000000.5", "9223372036854775808"})
    void 정수_long으로_정확히_표현할_수_없는_금액을_거부한다(String invalidAmount) {
        when(repository.findAll(any(HousingComplexSearchCondition.class))).thenReturn(List.of(row(
                1L,
                "잘못된 금액 단지",
                "HAPPY_HOUSING",
                "LH",
                invalidAmount,
                null,
                null,
                null
        )));

        assertThrows(ArithmeticException.class, () -> service.getComplexesForMap(baseSearchRequest()));
    }

    private HousingComplexSearchRequest fullSearchRequest() {
        return new HousingComplexSearchRequest(
                " 행복 단지 ",
                "12210",
                List.of(RentalType.HAPPY_HOUSING, RentalType.NATIONAL_RENTAL),
                List.of(ApplicationStatus.APPLYING, ApplicationStatus.CLOSED),
                List.of(AgencyCode.LH, AgencyCode.SH),
                List.of(RecruitmentType.NEW, RecruitmentType.WAITLIST),
                10_000_000L,
                70_000_000L,
                100_000L,
                300_000L,
                new BigDecimal("36.12"),
                new BigDecimal("44.87"),
                2018,
                2026,
                true,
                new BigDecimal("37.400000"),
                new BigDecimal("126.800000"),
                new BigDecimal("37.600000"),
                new BigDecimal("127.100000")
        );
    }

    private HousingComplexSearchRequest baseSearchRequest() {
        return new HousingComplexSearchRequest(
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
                null,
                null,
                null,
                null,
                BOUNDS.southWestLat(),
                BOUNDS.southWestLng(),
                BOUNDS.northEastLat(),
                BOUNDS.northEastLng()
        );
    }

    private ComplexSummaryRow row(
            long complexId,
            String name,
            String rentalType,
            String agencyCode,
            String depositMin,
            String depositMax,
            String monthlyRentMin,
            String monthlyRentMax
    ) {
        return new ComplexSummaryRow(
                complexId,
                name,
                null,
                "11",
                "11140",
                rentalType,
                agencyCode,
                new BigDecimal("37.500000"),
                new BigDecimal("126.900000"),
                new BigDecimal("36.12"),
                new BigDecimal("44.87"),
                amount(depositMin),
                amount(depositMax),
                amount(monthlyRentMin),
                amount(monthlyRentMax),
                null,
                null,
                null,
                null,
                null,
                LocalDate.of(2020, 1, 1)
        );
    }

    private BigDecimal amount(String value) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(value);
    }
}
