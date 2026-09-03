package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.dto.response.CurrentAnnouncementResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexDetailResponse;
import com.toadzip.backend.housing.dto.response.HousingTypeDetailResponse;
import com.toadzip.backend.housing.exception.HousingComplexNotFoundException;
import com.toadzip.backend.housing.repository.ComplexDetailQueryRepository;
import com.toadzip.backend.housing.repository.ComplexDetailRow;
import com.toadzip.backend.housing.repository.ComplexSummaryQueryRepository;
import com.toadzip.backend.housing.repository.CurrentAnnouncementRow;
import com.toadzip.backend.housing.repository.CurrentAnnouncementTargetRow;
import com.toadzip.backend.housing.repository.CurrentSupplyConditionRow;
import com.toadzip.backend.housing.repository.HousingTypeDetailRow;
import com.toadzip.backend.region.repository.RegionCodeResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HousingComplexDetailQueryTest {

    private static final long COMPLEX_ID = 17L;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-26T15:00:00Z"),
            ZoneOffset.UTC
    );

    private ComplexDetailQueryRepository detailRepository;

    private HousingComplexQueryService service;

    @BeforeEach
    void setUp() {
        ComplexSummaryQueryRepository summaryRepository = mock(ComplexSummaryQueryRepository.class);
        HousingComplexSummaryMapper summaryMapper = mock(HousingComplexSummaryMapper.class);
        detailRepository = mock(ComplexDetailQueryRepository.class);
        RegionCodeResolver regionCodeResolver = (provinceCode, cityCountyDistrictCode) -> {
            if ("11".equals(provinceCode) && "11140".equals(cityCountyDistrictCode)) {
                return Optional.of("서울특별시 중구");
            }
            return Optional.empty();
        };
        HousingComplexCodeMapper codeMapper = new HousingComplexCodeMapper();
        HousingComplexDetailMapper detailMapper = new HousingComplexDetailMapper(codeMapper, regionCodeResolver);
        RegionCodeResolver noOpSearchRegionCodeResolver = (provinceCode, cityCountyDistrictCode) -> Optional.empty();
        service = new HousingComplexQueryService(
                summaryRepository,
                summaryMapper,
                detailRepository,
                detailMapper,
                new HousingComplexSearchRequestNormalizer(noOpSearchRegionCodeResolver, CLOCK),
                CLOCK
        );
        stubDetail(complexRow("https://example.com/complex.png", "INDIVIDUAL", "APARTMENT", "STAIR"));
    }

    @Test
    void 단지_상세를_공개_응답으로_조립하고_주택형별_공급조건을_순서대로_그룹화한다() {
        HousingComplexDetailResponse response = getExistingComplex();

        assertAll(
                () -> assertEquals(COMPLEX_ID, response.complexId()),
                () -> assertEquals("행복 단지", response.name()),
                () -> assertEquals("HAPPY_HOUSING", response.rentalType()),
                () -> assertEquals("LH", response.agency().code()),
                () -> assertEquals("서울특별시 중구", response.address().regionName()),
                () -> assertEquals("서울특별시 중구 세종대로 110", response.address().roadAddress()),
                () -> assertEquals(new BigDecimal("37.500000"), response.address().latitude()),
                () -> assertEquals(new BigDecimal("126.900000"), response.address().longitude()),
                () -> assertEquals(LocalDate.of(2020, 1, 1), response.completionDate()),
                () -> assertEquals("APARTMENT", response.buildingType()),
                () -> assertEquals(Boolean.TRUE, response.hasElevator()),
                () -> assertEquals("INDIVIDUAL", response.heatingType()),
                () -> assertEquals("STAIR", response.corridorType()),
                () -> assertEquals(7, response.moveOutCountLastYear()),
                () -> assertEquals(100, response.totalHouseholdCount()),
                () -> assertEquals(80, response.totalParkingCount()),
                () -> assertEquals(List.of("https://example.com/complex.png"), response.images()),
                () -> assertNull(response.overviewImageUrl()),
                () -> assertEquals(List.of(101L, 102L), housingTypeIds(response)),
                () -> assertEquals(List.of("대학생", "청년"), supplyTargets(response.housingTypes().getFirst())),
                () -> assertEquals(List.of("신혼부부"), supplyTargets(response.housingTypes().getLast()))
        );
    }

    @Test
    void 상세의_금액을_longValueExact로_변환하고_저장된_실제_경쟁률을_반환한다() {
        HousingComplexDetailResponse response = getExistingComplex();
        HousingTypeDetailResponse firstType = response.housingTypes().getFirst();
        HousingTypeDetailResponse secondType = response.housingTypes().getLast();

        assertAll(
                () -> assertNull(firstType.supplyArea()),
                () -> assertNull(firstType.floorPlan3dImageUrl()),
                () -> assertEquals(Boolean.FALSE, firstType.isDuplex()),
                () -> assertNull(firstType.maintenanceFee()),
                () -> assertEquals(123456L, secondType.maintenanceFee()),
                () -> assertEquals(10000000L, firstType.currentSupplyConditions().getFirst().deposit()),
                () -> assertEquals(100000L, firstType.currentSupplyConditions().getFirst().monthlyRent()),
                () -> assertEquals(1000000L,
                        firstType.currentSupplyConditions().getFirst().convertibleDeposit()),
                () -> assertNull(firstType.currentSupplyConditions().getLast().convertibleDeposit()),
                () -> assertEquals(
                        new BigDecimal("2.5000"),
                        response.currentAnnouncements().getFirst().actualCompetitionRate()
                )
        );
    }

    @Test
    void 저장_이미지가_null이면_빈_이미지_목록과_nullable_기본정보를_반환한다() {
        stubDetail(new ComplexDetailRow(
                COMPLEX_ID,
                "행복 단지",
                null,
                "11",
                "11140",
                "서울특별시 중구 세종대로 110",
                "HAPPY_HOUSING",
                "LH",
                new BigDecimal("37.500000"),
                new BigDecimal("126.900000"),
                LocalDate.of(2020, 1, 1),
                "APARTMENT",
                true,
                "INDIVIDUAL",
                "STAIR",
                null,
                100,
                80
        ));

        HousingComplexDetailResponse response = getExistingComplex();

        assertAll(
                () -> assertEquals(List.of(), response.images()),
                () -> assertNull(response.moveOutCountLastYear())
        );
    }

    @Test
    void 주택형_복층_여부가_없으면_null로_반환한다() {
        stubDetail(new ComplexDetailRow(
                COMPLEX_ID,
                "행복 단지",
                null,
                "11",
                "11140",
                "서울특별시 중구 세종대로 110",
                "HAPPY_HOUSING",
                "LH",
                new BigDecimal("37.500000"),
                new BigDecimal("126.900000"),
                null,
                "APARTMENT",
                null,
                null,
                null,
                null,
                100,
                80
        ));
        when(detailRepository.findHousingTypes(COMPLEX_ID)).thenReturn(List.of(new HousingTypeDetailRow(
                101L,
                "46A",
                new BigDecimal("46.8000"),
                null,
                null,
                null,
                null
        )));

        HousingComplexDetailResponse response = getExistingComplex();

        assertNull(response.housingTypes().getFirst().isDuplex());
    }

    @Test
    void 서울_오늘로_현재공고_상태와_D_Day와_대상순서를_계산한다() {
        HousingComplexDetailResponse response = getExistingComplex();
        CurrentAnnouncementResponse beforeApplication = response.currentAnnouncements().getFirst();
        CurrentAnnouncementResponse applying = response.currentAnnouncements().getLast();

        assertAll(
                () -> assertEquals(List.of(202L, 201L), announcementIds(response)),
                () -> assertEquals("CORRECTION", beforeApplication.publicationType()),
                () -> assertEquals("BEFORE_APPLICATION", beforeApplication.applicationStatus()),
                () -> assertEquals(List.of("청년", "신혼부부"), beforeApplication.targets()),
                () -> assertEquals(LocalDate.of(2026, 8, 28), beforeApplication.applicationStartAt()),
                () -> assertEquals(LocalDate.of(2026, 8, 30), beforeApplication.applicationEndAt()),
                () -> assertEquals(3, beforeApplication.dDay()),
                () -> assertEquals("APPLYING", applying.applicationStatus()),
                () -> assertEquals(0, applying.dDay())
        );
    }

    @Test
    void 없는_단지는_HousingComplexNotFoundException을_던진다() {
        when(detailRepository.findComplex(999L)).thenReturn(Optional.empty());

        assertThrows(HousingComplexNotFoundException.class, () -> service.getComplex(999L));
    }

    @Test
    void 소수_금액은_longValueExact_변환에_실패한다() {
        when(detailRepository.findCurrentSupplyConditions(COMPLEX_ID, TODAY)).thenReturn(List.of(
                new CurrentSupplyConditionRow(
                        201L,
                        301L,
                        101L,
                        401L,
                        "청년",
                        new BigDecimal("1.50"),
                        new BigDecimal("100000.00"),
                        null
                )
        ));

        assertThrows(ArithmeticException.class, () -> service.getComplex(COMPLEX_ID));
    }

    @ParameterizedTest
    @CsvSource({
            "INDIVIDUAL, APARTMENT, STAIR, INDIVIDUAL, APARTMENT, STAIR",
            "개별난방, 아파트, 계단식, INDIVIDUAL, APARTMENT, STAIR",
            "CENTRAL, OFFICETEL, CORRIDOR, CENTRAL, OFFICETEL, CORRIDOR",
            "중앙난방, 오피스텔, 복도식, CENTRAL, OFFICETEL, CORRIDOR",
            "DISTRICT, ETC, MIXED, DISTRICT, ETC, MIXED",
            "지역난방, 기타, 혼합식, DISTRICT, ETC, MIXED",
            "ETC, APARTMENT, UNKNOWN, ETC, APARTMENT, UNKNOWN",
            "기타, 아파트, 미상, ETC, APARTMENT, UNKNOWN"
    })
    void canonical과_legacy_상세코드를_canonical_코드로_반환한다(
            String heatingType,
            String buildingType,
            String corridorType,
            String expectedHeatingType,
            String expectedBuildingType,
            String expectedCorridorType
    ) {
        stubDetail(complexRow(null, heatingType, buildingType, corridorType));

        HousingComplexDetailResponse response = getExistingComplex();

        assertAll(
                () -> assertEquals(expectedHeatingType, response.heatingType()),
                () -> assertEquals(expectedBuildingType, response.buildingType()),
                () -> assertEquals(expectedCorridorType, response.corridorType())
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"heating", "building", "corridor"})
    void 알_수_없는_nonnull_상세코드는_명시적으로_실패한다(String field) {
        String heatingType = "INDIVIDUAL";
        String buildingType = "APARTMENT";
        String corridorType = "STAIR";
        if ("heating".equals(field)) {
            heatingType = "UNKNOWN_HEATING";
        }
        if ("building".equals(field)) {
            buildingType = "UNKNOWN_BUILDING";
        }
        if ("corridor".equals(field)) {
            corridorType = "UNKNOWN_CORRIDOR";
        }
        stubDetail(complexRow(null, heatingType, buildingType, corridorType));

        assertThrows(IllegalStateException.class, () -> service.getComplex(COMPLEX_ID));
    }

    @Test
    void 저장된_지역코드_pair를_해석할_수_없으면_데이터_무결성_실패로_처리한다() {
        ComplexDetailRow row = complexRow(null, "INDIVIDUAL", "APARTMENT", "STAIR");
        stubDetail(new ComplexDetailRow(
                row.complexId(),
                row.name(),
                row.imageUrl(),
                "99",
                "99999",
                row.roadAddress(),
                row.rentalType(),
                row.agencyCode(),
                row.latitude(),
                row.longitude(),
                row.completionDate(),
                row.buildingType(),
                row.hasElevator(),
                row.heatingType(),
                row.corridorType(),
                row.moveOutCountLastYear(),
                row.totalHouseholdCount(),
                row.totalParkingCount()
        ));

        assertThrows(IllegalStateException.class, () -> service.getComplex(COMPLEX_ID));
    }

    private HousingComplexDetailResponse getExistingComplex() {
        return assertDoesNotThrow(() -> service.getComplex(COMPLEX_ID));
    }

    private void stubDetail(ComplexDetailRow complex) {
        when(detailRepository.findComplex(COMPLEX_ID)).thenReturn(Optional.of(complex));
        when(detailRepository.findHousingTypes(COMPLEX_ID)).thenReturn(List.of(
                new HousingTypeDetailRow(
                        101L,
                        "36A",
                        new BigDecimal("36.12"),
                        null,
                        "https://example.com/36a.png",
                        false,
                        null
                ),
                new HousingTypeDetailRow(
                        102L,
                        "44B",
                        new BigDecimal("44.87"),
                        new BigDecimal("51.10"),
                        "https://example.com/44b.png",
                        true,
                        new BigDecimal("123456.00")
                )
        ));
        when(detailRepository.findCurrentSupplyConditions(COMPLEX_ID, TODAY)).thenReturn(List.of(
                new CurrentSupplyConditionRow(
                        202L,
                        302L,
                        102L,
                        403L,
                        "신혼부부",
                        new BigDecimal("30000000.00"),
                        new BigDecimal("300000.00"),
                        new BigDecimal("3000000.00")
                ),
                new CurrentSupplyConditionRow(
                        201L,
                        301L,
                        101L,
                        401L,
                        "대학생",
                        new BigDecimal("10000000.00"),
                        new BigDecimal("100000.00"),
                        new BigDecimal("1000000.00")
                ),
                new CurrentSupplyConditionRow(
                        201L,
                        301L,
                        101L,
                        402L,
                        "청년",
                        new BigDecimal("20000000.00"),
                        new BigDecimal("200000.00"),
                        null
                )
        ));
        when(detailRepository.findCurrentAnnouncements(COMPLEX_ID, TODAY)).thenReturn(List.of(
                new CurrentAnnouncementRow(
                        202L,
                        "정정 공고",
                        "CORRECTION",
                        LocalDate.of(2026, 8, 26),
                        LocalDate.of(2026, 8, 28),
                        LocalDate.of(2026, 8, 30),
                        new BigDecimal("2.5000")
                ),
                new CurrentAnnouncementRow(
                        201L,
                        "원 공고",
                        "원공고",
                        LocalDate.of(2026, 8, 25),
                        LocalDate.of(2026, 8, 20),
                        TODAY,
                        null
                )
        ));
        when(detailRepository.findCurrentAnnouncementTargets(COMPLEX_ID, TODAY)).thenReturn(List.of(
                new CurrentAnnouncementTargetRow(202L, 302L, 403L, "청년"),
                new CurrentAnnouncementTargetRow(202L, 303L, 404L, "신혼부부"),
                new CurrentAnnouncementTargetRow(201L, 301L, 401L, "대학생")
        ));
    }

    private ComplexDetailRow complexRow(
            String imageUrl,
            String heatingType,
            String buildingType,
            String corridorType
    ) {
        return new ComplexDetailRow(
                COMPLEX_ID,
                "행복 단지",
                imageUrl,
                "11",
                "11140",
                "서울특별시 중구 세종대로 110",
                "행복주택",
                "한국토지주택공사",
                new BigDecimal("37.500000"),
                new BigDecimal("126.900000"),
                LocalDate.of(2020, 1, 1),
                buildingType,
                true,
                heatingType,
                corridorType,
                7,
                100,
                80
        );
    }

    private List<Long> housingTypeIds(HousingComplexDetailResponse response) {
        return response.housingTypes().stream().map(HousingTypeDetailResponse::housingTypeId).toList();
    }

    private List<String> supplyTargets(HousingTypeDetailResponse housingType) {
        return housingType.currentSupplyConditions().stream().map(condition -> condition.target()).toList();
    }

    private List<Long> announcementIds(HousingComplexDetailResponse response) {
        return response.currentAnnouncements().stream()
                .map(CurrentAnnouncementResponse::announcementId)
                .toList();
    }
}
