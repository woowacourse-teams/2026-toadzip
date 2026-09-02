package com.toadzip.backend.housing.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.ComplexSort;
import com.toadzip.backend.housing.domain.MapBounds;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.housing.dto.request.HousingComplexSearchRequest;
import com.toadzip.backend.housing.dto.response.HousingComplexDetailResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexListResponse;
import com.toadzip.backend.housing.dto.response.HousingComplexMapResponse;
import com.toadzip.backend.housing.exception.HousingComplexNotFoundException;
import com.toadzip.backend.housing.exception.InvalidComplexRequestException;
import com.toadzip.backend.housing.exception.InvalidRegionCodeException;
import com.toadzip.backend.housing.repository.ComplexDetailQueryRepository;
import com.toadzip.backend.housing.repository.ComplexDetailRow;
import com.toadzip.backend.housing.repository.ComplexSummaryCursor;
import com.toadzip.backend.housing.repository.ComplexSummaryQueryRepository;
import com.toadzip.backend.housing.repository.ComplexSummaryRow;
import com.toadzip.backend.housing.repository.HousingComplexSearchCondition;
import com.toadzip.backend.region.repository.RegionCodeResolver;

@Service
public class HousingComplexQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final ComplexSummaryQueryRepository repository;

    private final ComplexDetailQueryRepository detailRepository;

    private final HousingComplexSummaryMapper summaryMapper;

    private final HousingComplexDetailMapper detailMapper;

    private final HousingComplexCursorCodec cursorCodec;

    private final RegionCodeResolver regionCodeResolver;

    private final Clock clock;

    @Autowired
    public HousingComplexQueryService(
            ComplexSummaryQueryRepository repository,
            HousingComplexSummaryMapper summaryMapper,
            ComplexDetailQueryRepository detailRepository,
            HousingComplexDetailMapper detailMapper,
            RegionCodeResolver regionCodeResolver,
            Clock clock
    ) {
        this.repository = repository;
        this.summaryMapper = summaryMapper;
        this.detailRepository = detailRepository;
        this.detailMapper = detailMapper;
        this.cursorCodec = new HousingComplexCursorCodec();
        this.regionCodeResolver = regionCodeResolver;
        this.clock = clock;
    }

    HousingComplexQueryService(
            ComplexSummaryQueryRepository repository,
            HousingComplexSummaryMapper summaryMapper,
            RegionCodeResolver regionCodeResolver,
            Clock clock
    ) {
        this(repository, summaryMapper, null, null, regionCodeResolver, clock);
    }

    @Transactional(readOnly = true)
    public HousingComplexMapResponse getComplexesForMap(HousingComplexSearchRequest request) {
        HousingComplexSearchCondition condition = searchCondition(request);
        return new HousingComplexMapResponse(repository.findAll(condition).stream()
                .map(summaryMapper::toMapItem)
                .toList());
    }

    @Transactional(readOnly = true)
    public HousingComplexListResponse getComplexes(
            HousingComplexSearchRequest request,
            ComplexSort sort,
            String cursor,
            int size
    ) {
        requireRequest(request);
        MapBounds bounds = bounds(request);
        requireValidSize(size);
        ComplexSort normalizedSort = normalizedSort(sort);
        HousingComplexSearchCondition condition = searchCondition(request, bounds);
        ComplexSummaryCursor decodedCursor = decodeCursor(cursor, normalizedSort);
        List<ComplexSummaryRow> fetched = repository.findPage(
                condition,
                normalizedSort,
                decodedCursor,
                size + 1
        );
        boolean hasNext = fetched.size() > size;
        List<ComplexSummaryRow> page = fetched.stream().limit(size).toList();
        return new HousingComplexListResponse(
                summaryMapper.toListItems(page, condition.today()),
                nextCursor(page, hasNext, normalizedSort),
                hasNext
        );
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HousingComplexDetailResponse getComplex(long complexId) {
        LocalDate today = today();
        ComplexDetailRow complex = detailRepository.findComplex(complexId)
                .orElseThrow(HousingComplexNotFoundException::new);
        return detailMapper.toResponse(
                complex,
                detailRepository.findHousingTypes(complexId),
                detailRepository.findCurrentSupplyConditions(complexId, today),
                detailRepository.findCurrentAnnouncements(complexId, today),
                detailRepository.findCurrentAnnouncementTargets(complexId, today),
                today
        );
    }

    private void requireValidSize(int size) {
        if (size < 1 || size > 50) {
            throw new InvalidComplexRequestException();
        }
    }

    private HousingComplexSearchCondition searchCondition(HousingComplexSearchRequest request) {
        requireRequest(request);
        return searchCondition(request, bounds(request));
    }

    private HousingComplexSearchCondition searchCondition(
            HousingComplexSearchRequest request,
            MapBounds bounds
    ) {
        String keyword = normalizedKeyword(request.keyword());
        Set<RentalType> rentalTypes = immutableSet(request.rentalTypes());
        Set<ApplicationStatus> applicationStatuses = immutableSet(request.applicationStatuses());
        Set<AgencyCode> agencyCodes = immutableSet(request.agencyCodes());
        Set<RecruitmentType> recruitmentTypes = immutableSet(request.recruitmentTypes());
        requireAllowedApplicationStatuses(applicationStatuses);
        requireNonNegative(request.minDeposit());
        requireNonNegative(request.maxDeposit());
        requireNonNegative(request.minMonthlyRent());
        requireNonNegative(request.maxMonthlyRent());
        requireNonNegative(request.minExclusiveArea());
        requireNonNegative(request.maxExclusiveArea());
        requireAscending(request.minDeposit(), request.maxDeposit());
        requireAscending(request.minMonthlyRent(), request.maxMonthlyRent());
        requireAscending(request.minExclusiveArea(), request.maxExclusiveArea());
        requireValidYear(request.builtYearFrom());
        requireValidYear(request.builtYearTo());
        requireAscending(request.builtYearFrom(), request.builtYearTo());
        RegionSelection region = regionSelection(request.regionCode());
        return new HousingComplexSearchCondition(
                bounds,
                keyword,
                region.provinceCode(),
                region.districtCodes(),
                rentalTypes,
                applicationStatuses,
                agencyCodes,
                recruitmentTypes,
                decimal(request.minDeposit()),
                decimal(request.maxDeposit()),
                decimal(request.minMonthlyRent()),
                decimal(request.maxMonthlyRent()),
                request.minExclusiveArea(),
                request.maxExclusiveArea(),
                request.builtYearFrom(),
                request.builtYearTo(),
                request.hasElevator(),
                today()
        );
    }

    private void requireRequest(HousingComplexSearchRequest request) {
        if (request == null) {
            throw new InvalidComplexRequestException();
        }
    }

    private MapBounds bounds(HousingComplexSearchRequest request) {
        return MapBounds.of(
                request.southWestLat(),
                request.southWestLng(),
                request.northEastLat(),
                request.northEastLng()
        );
    }

    private ComplexSort normalizedSort(ComplexSort sort) {
        if (sort == null) {
            return ComplexSort.LATEST_ANNOUNCEMENT;
        }
        return sort;
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

    private void requireAllowedApplicationStatuses(Set<ApplicationStatus> statuses) {
        if (statuses.contains(ApplicationStatus.CANCELLED)) {
            throw new InvalidComplexRequestException();
        }
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

    private ComplexSummaryCursor decodeCursor(String cursor, ComplexSort sort) {
        if (cursor == null) {
            return null;
        }
        return cursorCodec.decode(cursor, sort);
    }

    private String nextCursor(List<ComplexSummaryRow> page, boolean hasNext, ComplexSort sort) {
        if (!hasNext) {
            return null;
        }
        ComplexSummaryRow finalItem = page.getLast();
        return cursorCodec.encode(cursorOf(finalItem, sort));
    }

    private ComplexSummaryCursor cursorOf(ComplexSummaryRow row, ComplexSort sort) {
        return switch (sort) {
            case LATEST_ANNOUNCEMENT -> dateCursor(sort, row.postedDate(), row.complexId());
            case DEPOSIT_ASC -> decimalCursor(sort, row.depositMin(), row.complexId());
            case MONTHLY_RENT_ASC -> decimalCursor(sort, row.monthlyRentMin(), row.complexId());
            case AREA_DESC -> decimalCursor(sort, row.exclusiveAreaMax(), row.complexId());
            case COMPLETION_DATE_DESC -> dateCursor(sort, row.completionDate(), row.complexId());
        };
    }

    private ComplexSummaryCursor dateCursor(ComplexSort sort, LocalDate value, long complexId) {
        ComplexSummaryCursor.DateValue primaryValue = null;
        if (value != null) {
            primaryValue = new ComplexSummaryCursor.DateValue(value);
        }
        return new ComplexSummaryCursor(sort, primaryValue, complexId);
    }

    private ComplexSummaryCursor decimalCursor(ComplexSort sort, BigDecimal value, long complexId) {
        ComplexSummaryCursor.DecimalValue primaryValue = null;
        if (value != null) {
            primaryValue = new ComplexSummaryCursor.DecimalValue(value);
        }
        return new ComplexSummaryCursor(sort, primaryValue, complexId);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SEOUL_ZONE);
    }

    private record RegionSelection(String provinceCode, Set<String> districtCodes) {
        private RegionSelection {
            districtCodes = Set.copyOf(districtCodes);
        }
    }
}
