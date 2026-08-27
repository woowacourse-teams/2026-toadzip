package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.dto.response.HousingComplexListItemResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexListResponse;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor;
import com.toadzip.backend.housing.repository.ComplexSummaryQueryRepository;
import com.toadzip.backend.housing.repository.ComplexSummaryRow;
import com.toadzip.backend.housing.service.HousingComplexCursorCodec.HousingComplexCursor;
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

    private HousingComplexQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ComplexSummaryQueryRepository.class);
        RegionCodeResolver regionCodeResolver = (provinceCode, cityCountyDistrictCode) -> {
            if ("11".equals(provinceCode) && "11140".equals(cityCountyDistrictCode)) {
                return Optional.of("서울특별시 중구");
            }
            return Optional.empty();
        };
        HousingComplexSummaryMapper summaryMapper = new HousingComplexSummaryMapper(
                new HousingComplexCodeMapper(),
                regionCodeResolver
        );
        service = new HousingComplexQueryService(repository, summaryMapper, CLOCK);
    }

    @Test
    void size보다_하나_더_조회해_다음_페이지_커서를_만든다() {
        when(repository.findFirstPage(BOUNDS, 3)).thenReturn(List.of(
                row(10L, LocalDate.of(2026, 8, 21), "ORIGINAL", LocalDate.of(2026, 8, 22),
                        LocalDate.of(2026, 8, 29)),
                row(9L, LocalDate.of(2026, 8, 20), "ORIGINAL", LocalDate.of(2026, 8, 22),
                        LocalDate.of(2026, 8, 29)),
                row(8L, LocalDate.of(2026, 8, 19), "ORIGINAL", LocalDate.of(2026, 8, 22),
                        LocalDate.of(2026, 8, 29))
        ));

        HousingComplexListResponse response = service.getComplexes(BOUNDS, null, 2);

        assertAll(
                () -> assertEquals(2, response.items().size()),
                () -> assertEquals(List.of(10L, 9L), ids(response)),
                () -> assertTrue(response.hasNext()),
                () -> assertNotNull(response.nextCursor()),
                () -> assertEquals(
                        new HousingComplexCursor(LocalDate.of(2026, 8, 20), 9L),
                        new HousingComplexCursorCodec().decode(response.nextCursor())
                )
        );
    }

    @Test
    void null_게시일_커서에서도_반환한_페이지의_마지막_단지로_다음_커서를_만든다() {
        ComplexSummaryCursor cursor = new ComplexSummaryCursor(null, 40L);
        when(repository.findPageAfter(BOUNDS, cursor, 2)).thenReturn(List.of(
                rowWithoutAnnouncement(30L),
                rowWithoutAnnouncement(20L)
        ));

        String encodedCursor = new HousingComplexCursorCodec().encode(null, 40L);
        HousingComplexListResponse response = service.getComplexes(BOUNDS, encodedCursor, 1);

        assertAll(
                () -> assertEquals(List.of(30L), ids(response)),
                () -> assertTrue(response.hasNext()),
                () -> assertEquals(
                        new HousingComplexCursor(null, 30L),
                        new HousingComplexCursorCodec().decode(response.nextCursor())
                )
        );
    }

    @Test
    void 추가_행이_없으면_다음_커서를_반환하지_않는다() {
        when(repository.findFirstPage(BOUNDS, 3)).thenReturn(List.of(rowWithoutAnnouncement(10L)));

        HousingComplexListResponse response = service.getComplexes(BOUNDS, null, 2);

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
                () -> service.getComplexes(BOUNDS, null, size)
        );
    }

    @Test
    void 단지와_대표공고_요약을_공개_목록_계약으로_변환한다() {
        when(repository.findFirstPage(BOUNDS, 2)).thenReturn(List.of(row(
                17L,
                LocalDate.of(2026, 8, 20),
                "ORIGINAL",
                LocalDate.of(2026, 8, 28),
                LocalDate.of(2026, 8, 30)
        )));

        HousingComplexListResponse response = service.getComplexes(BOUNDS, null, 1);
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
        when(repository.findFirstPage(BOUNDS, 2)).thenReturn(List.of(row(
                1L,
                LocalDate.of(2026, 8, 20),
                storedValue,
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 30)
        )));

        HousingComplexListResponse response = service.getComplexes(BOUNDS, null, 1);
        assertEquals(1, response.items().size());

        assertEquals(expectedCode, response.items().getFirst().representativeAnnouncement().publicationType());
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
        when(repository.findFirstPage(BOUNDS, 2)).thenReturn(List.of(row(
                1L,
                LocalDate.of(2026, 8, 20),
                "ORIGINAL",
                applicationStartDate,
                applicationEndDate
        )));

        HousingComplexListResponse response = service.getComplexes(BOUNDS, null, 1);
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
        when(repository.findFirstPage(BOUNDS, 2)).thenReturn(List.of(row(
                1L,
                LocalDate.of(2026, 8, 20),
                storedValue,
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 30)
        )));

        assertThrows(IllegalStateException.class, () -> service.getComplexes(BOUNDS, null, 1));
    }

    @Test
    void 대표공고가_없는_단지는_대표공고를_null로_반환한다() {
        when(repository.findFirstPage(BOUNDS, 2)).thenReturn(List.of(rowWithoutAnnouncement(1L)));

        HousingComplexListResponse response = service.getComplexes(BOUNDS, null, 1);
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
                null
        );
        when(repository.findFirstPage(BOUNDS, 2)).thenReturn(List.of(unresolved));

        assertThrows(IllegalStateException.class, () -> service.getComplexes(BOUNDS, null, 1));
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
                applicationEndDate
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
                null
        );
    }

    private List<Long> ids(HousingComplexListResponse response) {
        return response.items().stream().map(HousingComplexListItemResponse::complexId).toList();
    }
}
