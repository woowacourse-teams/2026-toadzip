package com.toadzip.backend.housing.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.repository.HousingComplexFilterCondition;
import com.toadzip.backend.region.repository.RegionCodeResolver;

class HousingComplexSearchRequestNormalizerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-26T15:30:00Z"),
            ZoneOffset.UTC
    );

    private RegionCodeResolver regionCodeResolver;

    private HousingComplexSearchRequestNormalizer normalizer;

    @BeforeEach
    void setUp() {
        regionCodeResolver = mock(RegionCodeResolver.class);
        when(regionCodeResolver.filterCodes("12210"))
                .thenReturn(Optional.of(Set.of("12210", "29110")));
        normalizer = new HousingComplexSearchRequestNormalizer(regionCodeResolver, CLOCK);
    }

    @Test
    void bounds_정규화는_검색필터를_검증하지_않는다() {
        HousingComplexSearchRequest request = request("   ", null, validBounds());

        MapBounds bounds = normalizer.normalizeBounds(request);

        assertEquals(validBounds(), bounds);
    }

    @Test
    void 필터_정규화는_bounds를_검증하지_않는다() {
        HousingComplexSearchRequest request = request(" 행복 단지 ", true, null);

        HousingComplexFilterCondition filters = normalizer.normalizeFilters(request);

        assertEquals("행복 단지", filters.keyword());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = {true, false})
    void 필터를_정규화하며_모집중_공고_여부의_세_상태를_보존한다(
            Boolean hasActiveAnnouncement
    ) {
        HousingComplexSearchRequest request = request(" 행복 단지 ", hasActiveAnnouncement, null);

        HousingComplexFilterCondition filters = normalizer.normalizeFilters(request);

        assertAll(
                () -> assertEquals("행복 단지", filters.keyword()),
                () -> assertNull(filters.provinceCode()),
                () -> assertEquals(Set.of("12210", "29110"), filters.cityCountyDistrictCodes()),
                () -> assertEquals(Set.of(RentalType.HAPPY_HOUSING, RentalType.NATIONAL_RENTAL),
                        filters.rentalTypes()),
                () -> assertEquals(Set.of(ApplicationStatus.APPLYING, ApplicationStatus.CLOSED),
                        filters.applicationStatuses()),
                () -> assertEquals(Set.of(AgencyCode.LH, AgencyCode.SH), filters.agencyCodes()),
                () -> assertEquals(Set.of(RecruitmentType.NEW, RecruitmentType.WAITLIST),
                        filters.recruitmentTypes()),
                () -> assertEquals(new BigDecimal("10000000"), filters.minDeposit()),
                () -> assertEquals(new BigDecimal("70000000"), filters.maxDeposit()),
                () -> assertEquals(new BigDecimal("100000"), filters.minMonthlyRent()),
                () -> assertEquals(new BigDecimal("300000"), filters.maxMonthlyRent()),
                () -> assertEquals(new BigDecimal("36.12"), filters.minExclusiveArea()),
                () -> assertEquals(new BigDecimal("44.87"), filters.maxExclusiveArea()),
                () -> assertEquals(2018, filters.builtYearFrom()),
                () -> assertEquals(2026, filters.builtYearTo()),
                () -> assertEquals(true, filters.hasElevator()),
                () -> assertEquals(hasActiveAnnouncement, filters.hasActiveAnnouncement()),
                () -> assertEquals(LocalDate.of(2026, 8, 27), filters.today()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> filters.rentalTypes().add(RentalType.ETC))
        );
    }

    @Test
    void null_요청은_bounds와_필터_정규화에서_각각_거부한다() {
        assertAll(
                () -> assertThrows(InvalidComplexRequestException.class,
                        () -> normalizer.normalizeBounds(null)),
                () -> assertThrows(InvalidComplexRequestException.class,
                        () -> normalizer.normalizeFilters(null))
        );
    }

    private HousingComplexSearchRequest request(
            String keyword,
            Boolean hasActiveAnnouncement,
            MapBounds bounds
    ) {
        return new HousingComplexSearchRequest(
                keyword,
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
                coordinate(bounds, MapBounds::southWestLat),
                coordinate(bounds, MapBounds::southWestLng),
                coordinate(bounds, MapBounds::northEastLat),
                coordinate(bounds, MapBounds::northEastLng),
                hasActiveAnnouncement
        );
    }

    private BigDecimal coordinate(MapBounds bounds, Coordinate coordinate) {
        if (bounds == null) {
            return null;
        }
        return coordinate.from(bounds);
    }

    private MapBounds validBounds() {
        return MapBounds.of(
                new BigDecimal("37.400000"),
                new BigDecimal("126.800000"),
                new BigDecimal("37.600000"),
                new BigDecimal("127.100000")
        );
    }

    @FunctionalInterface
    private interface Coordinate {

        BigDecimal from(MapBounds bounds);
    }
}
