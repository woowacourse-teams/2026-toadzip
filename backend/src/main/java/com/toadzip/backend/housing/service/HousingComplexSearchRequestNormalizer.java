package com.toadzip.backend.housing.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.exception.InvalidRegionCodeException;
import com.toadzip.backend.housing.repository.HousingComplexFilterCondition;
import com.toadzip.backend.region.repository.RegionCodeResolver;

@Component
final class HousingComplexSearchRequestNormalizer {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final RegionCodeResolver regionCodeResolver;

    private final Clock clock;

    HousingComplexSearchRequestNormalizer(RegionCodeResolver regionCodeResolver, Clock clock) {
        this.regionCodeResolver = regionCodeResolver;
        this.clock = clock;
    }

    MapBounds normalizeBounds(HousingComplexSearchRequest request) {
        requireRequest(request);
        return MapBounds.of(
                request.southWestLat(), request.southWestLng(),
                request.northEastLat(), request.northEastLng()
        );
    }

    HousingComplexFilterCondition normalizeFilters(HousingComplexSearchRequest request) {
        requireRequest(request);
        String keyword = normalizedKeyword(request.keyword());
        FilterCollections collections = normalizedCollections(request);
        requireNonNegativeValues(request);
        requireAscendingValues(request);
        requireValidYears(request);
        RegionSelection region = regionSelection(request.regionCode());
        return toFilterCondition(request, keyword, collections, region);
    }

    private HousingComplexFilterCondition toFilterCondition(HousingComplexSearchRequest request, String keyword,
            FilterCollections collections, RegionSelection region) {
        return new HousingComplexFilterCondition(
                keyword, region.provinceCode(), region.districtCodes(),
                collections.rentalTypes(), collections.applicationStatuses(),
                collections.agencyCodes(), collections.recruitmentTypes(),
                decimal(request.minDeposit()), decimal(request.maxDeposit()),
                decimal(request.minMonthlyRent()), decimal(request.maxMonthlyRent()),
                request.minExclusiveArea(), request.maxExclusiveArea(),
                request.builtYearFrom(), request.builtYearTo(), request.hasElevator(),
                request.hasActiveAnnouncement(), today()
        );
    }

    private FilterCollections normalizedCollections(HousingComplexSearchRequest request) {
        return new FilterCollections(
                immutableSet(request.rentalTypes()),
                immutableSet(request.applicationStatuses()),
                immutableSet(request.agencyCodes()),
                immutableSet(request.recruitmentTypes())
        );
    }

    private void requireNonNegativeValues(HousingComplexSearchRequest request) {
        requireNonNegative(request.minDeposit());
        requireNonNegative(request.maxDeposit());
        requireNonNegative(request.minMonthlyRent());
        requireNonNegative(request.maxMonthlyRent());
        requireNonNegative(request.minExclusiveArea());
        requireNonNegative(request.maxExclusiveArea());
    }

    private void requireAscendingValues(HousingComplexSearchRequest request) {
        requireAscending(request.minDeposit(), request.maxDeposit());
        requireAscending(request.minMonthlyRent(), request.maxMonthlyRent());
        requireAscending(request.minExclusiveArea(), request.maxExclusiveArea());
    }

    private void requireValidYears(HousingComplexSearchRequest request) {
        requireValidYear(request.builtYearFrom());
        requireValidYear(request.builtYearTo());
        requireAscending(request.builtYearFrom(), request.builtYearTo());
    }

    private String normalizedKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = keyword.trim();
        if (normalized.isEmpty()) {
            throw new InvalidComplexRequestException();
        }
        return normalized;
    }

    private <T> Set<T> immutableSet(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new InvalidComplexRequestException();
        }
        return Set.copyOf(values);
    }

    private void requireNonNegative(Long value) {
        if (value != null && value < 0) {
            throw new InvalidComplexRequestException();
        }
    }

    private void requireNonNegative(BigDecimal value) {
        if (value != null && value.signum() < 0) {
            throw new InvalidComplexRequestException();
        }
    }

    private <T extends Comparable<T>> void requireAscending(T minimum, T maximum) {
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new InvalidComplexRequestException();
        }
    }

    private void requireValidYear(Integer year) {
        if (year != null && (year < 1 || year > 9999)) {
            throw new InvalidComplexRequestException();
        }
    }

    private RegionSelection regionSelection(String regionCode) {
        if (regionCode == null) {
            return new RegionSelection(null, Set.of());
        }
        if (regionCode.isBlank()) {
            throw new InvalidRegionCodeException();
        }
        if (regionCode.matches("[0-9]{2}") || regionCode.matches("[0-9]{5}")) {
            return filterSelection(regionCode);
        }
        throw new InvalidRegionCodeException();
    }

    private RegionSelection filterSelection(String regionCode) {
        Set<String> districtCodes = regionCodeResolver.filterCodes(regionCode)
                .orElseThrow(InvalidRegionCodeException::new);
        return new RegionSelection(null, districtCodes);
    }

    private BigDecimal decimal(Long value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value);
    }

    private void requireRequest(HousingComplexSearchRequest request) {
        if (request == null) {
            throw new InvalidComplexRequestException();
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SEOUL_ZONE);
    }

    private record FilterCollections(
            Set<RentalType> rentalTypes,
            Set<ApplicationStatus> applicationStatuses,
            Set<AgencyCode> agencyCodes,
            Set<RecruitmentType> recruitmentTypes
    ) {
    }

    private record RegionSelection(String provinceCode, Set<String> districtCodes) {

        private RegionSelection {
            districtCodes = Set.copyOf(districtCodes);
        }
    }
}
