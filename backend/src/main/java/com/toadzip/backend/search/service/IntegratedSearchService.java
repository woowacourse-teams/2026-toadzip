package com.toadzip.backend.search.service;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.region.repository.RegionCoordinateRepository;
import com.toadzip.backend.region.repository.RegionSearchRepository;
import com.toadzip.backend.region.repository.RegionSearchResult;
import com.toadzip.backend.search.domain.SearchMatch;
import com.toadzip.backend.search.domain.SearchType;
import com.toadzip.backend.search.dto.request.IntegratedSearchRequest;
import com.toadzip.backend.search.dto.response.IntegratedSearchResponse;
import com.toadzip.backend.search.dto.response.SearchFailureResponse;
import com.toadzip.backend.search.dto.response.SearchResultItemResponse;
import com.toadzip.backend.search.exception.InvalidSearchRequestException;
import com.toadzip.backend.search.repository.IntegratedSearchCondition;
import com.toadzip.backend.search.repository.InternalSearchRepository;
import com.toadzip.backend.search.repository.SearchSourceItem;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IntegratedSearchService {

    private static final Logger log = LoggerFactory.getLogger(IntegratedSearchService.class);
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final int PREVIEW_TOTAL_LIMIT = 8;
    private static final int PREVIEW_TYPE_LIMIT = 3;
    private static final int PAGE_SIZE = 20;
    private static final int TYPE_PAGE_SIZE = 5;
    private static final int MAX_PAGE = 100;

    private final InternalSearchRepository internalSearchRepository;
    private final RegionSearchRepository regionSearchRepository;
    private final RegionCoordinateRepository regionCoordinateRepository;
    private final Clock clock;

    public IntegratedSearchService(
            InternalSearchRepository internalSearchRepository,
            RegionSearchRepository regionSearchRepository,
            RegionCoordinateRepository regionCoordinateRepository,
            Clock clock
    ) {
        this.internalSearchRepository = internalSearchRepository;
        this.regionSearchRepository = regionSearchRepository;
        this.regionCoordinateRepository = regionCoordinateRepository;
        this.clock = clock;
    }

    public IntegratedSearchResponse search(IntegratedSearchRequest request) {
        SearchInput input = input(request);
        int fetchLimit = fetchLimit(input);
        IntegratedSearchCondition condition = new IntegratedSearchCondition(
                input.match(),
                input.rentalTypes(),
                input.applicationStatuses(),
                input.hasActiveAnnouncement(),
                LocalDate.ofInstant(clock.instant(), SEOUL_ZONE)
        );
        List<SearchFailureResponse> failures = new ArrayList<>();
        List<SearchSourceItem> results = new ArrayList<>();
        if (input.includes(SearchType.ANNOUNCEMENT)) {
            addAnnouncements(results, failures, condition, fetchLimit);
        }
        if (input.includes(SearchType.COMPLEX)) {
            addComplexes(results, failures, condition, fetchLimit);
        }
        if (input.includes(SearchType.REGION)) {
            addRegions(results, failures, input.match());
        }

        List<SearchResultItemResponse> ranked = orderedResults(results, input);
        Page page = resultPage(ranked, input);
        return new IntegratedSearchResponse(
                input.match().normalizedQuery(),
                itemsOfType(page, SearchType.ANNOUNCEMENT),
                itemsOfType(page, SearchType.COMPLEX),
                itemsOfType(page, SearchType.REGION),
                failures,
                input.page(),
                responseSize(input),
                page.hasNext(),
                totalCount(input, condition, ranked, failures)
        );
    }

    private Long totalCount(
            SearchInput input,
            IntegratedSearchCondition condition,
            List<SearchResultItemResponse> ranked,
            List<SearchFailureResponse> failures
    ) {
        if (input.type() == null || !failures.isEmpty()) {
            return null;
        }
        try {
            return switch (input.type()) {
                case ANNOUNCEMENT -> internalSearchRepository.countAnnouncements(condition);
                case COMPLEX -> internalSearchRepository.countComplexes(condition);
                case REGION -> (long) ranked.size();
            };
        } catch (RuntimeException exception) {
            log.error("{} 검색 건수 집계에 실패했습니다.", input.type(), exception);
            return null;
        }
    }

    private List<SearchResultItemResponse> orderedResults(List<SearchSourceItem> results, SearchInput input) {
        if (input.type() == SearchType.ANNOUNCEMENT || input.type() == SearchType.COMPLEX) {
            // Preserve the repository order when paging over its growing result prefix.
            return results.stream().map(this::response).toList();
        }
        return results.stream()
                .filter(item -> input.match().matches(item.title(), item.subtitle(), item.address()))
                .map(item -> new RankedItem(
                        item,
                        input.match().rank(item.title(), item.subtitle(), item.address())
                ))
                .sorted(resultOrder())
                .map(item -> response(item.source()))
                .toList();
    }

    private int fetchLimit(SearchInput input) {
        if (input.preview()) {
            return PREVIEW_TYPE_LIMIT + 1;
        }
        return (input.page() + 1) * input.size() + 1;
    }

    private int responseSize(SearchInput input) {
        if (input.preview()) {
            return PREVIEW_TOTAL_LIMIT;
        }
        return input.size();
    }

    private Page resultPage(List<SearchResultItemResponse> ranked, SearchInput input) {
        if (input.preview()) {
            return preview(ranked);
        }
        Page result = page(ranked, input.page(), input.size());
        if (input.type() != null && input.page() == MAX_PAGE) {
            return new Page(result.items(), false);
        }
        return result;
    }

    private void addAnnouncements(
            List<SearchSourceItem> results,
            List<SearchFailureResponse> failures,
            IntegratedSearchCondition condition,
            int limit
    ) {
        try {
            results.addAll(internalSearchRepository.findAnnouncements(condition, limit));
        } catch (RuntimeException exception) {
            log.error("공고 통합 검색에 실패했습니다.", exception);
            failures.add(failure(SearchType.ANNOUNCEMENT));
        }
    }

    private void addComplexes(
            List<SearchSourceItem> results,
            List<SearchFailureResponse> failures,
            IntegratedSearchCondition condition,
            int limit
    ) {
        try {
            results.addAll(internalSearchRepository.findComplexes(condition, limit));
        } catch (RuntimeException exception) {
            log.error("단지 통합 검색에 실패했습니다.", exception);
            failures.add(failure(SearchType.COMPLEX));
        }
    }

    private void addRegions(
            List<SearchSourceItem> results,
            List<SearchFailureResponse> failures,
            SearchMatch match
    ) {
        try {
            List<SearchSourceItem> regions = matchingRegions(match);
            results.addAll(regions);
        } catch (RuntimeException exception) {
            log.error("지역 통합 검색에 실패했습니다.", exception);
            failures.add(failure(SearchType.REGION));
        }
    }

    private List<SearchSourceItem> matchingRegions(SearchMatch match) {
        Set<String> matchingCodes = null;
        java.util.Map<String, com.toadzip.backend.region.repository.RegionSearchResult> regions =
                new java.util.LinkedHashMap<>();
        for (String token : match.tokens()) {
            List<com.toadzip.backend.region.repository.RegionSearchResult> matches =
                    regionSearchRepository.findByKeyword(token);
            Set<String> tokenCodes = matches.stream()
                    .map(com.toadzip.backend.region.repository.RegionSearchResult::regionCode)
                    .collect(java.util.stream.Collectors.toSet());
            matches.forEach(region -> regions.put(region.regionCode(), region));
            if (matchingCodes == null) {
                matchingCodes = new HashSet<>(tokenCodes);
            } else {
                matchingCodes.retainAll(tokenCodes);
            }
        }
        if (matchingCodes == null) {
            return List.of();
        }
        return matchingCodes.stream()
                .map(regions::get)
                .filter(Objects::nonNull)
                .map(this::regionSource)
                .toList();
    }

    private SearchSourceItem regionSource(RegionSearchResult region) {
        var coordinate = regionCoordinateRepository.findByRegionCode(region.regionCode());
        return new SearchSourceItem(
                SearchType.REGION,
                region.regionCode(),
                region.displayName(),
                region.provinceName(),
                region.displayName(),
                coordinate.map(point -> point.latitude()).orElse(null),
                coordinate.map(point -> point.longitude()).orElse(null),
                null,
                null,
                region.regionCode()
        );
    }

    private SearchFailureResponse failure(SearchType type) {
        return new SearchFailureResponse(type, typeName(type) + " 검색에 실패했습니다. 다시 시도해 주세요.");
    }

    private String typeName(SearchType type) {
        return switch (type) {
            case ANNOUNCEMENT -> "공고";
            case COMPLEX -> "단지";
            case REGION -> "지역";
        };
    }

    private SearchResultItemResponse response(SearchSourceItem item) {
        return new SearchResultItemResponse(
                item.type(),
                item.id(),
                item.title(),
                item.subtitle(),
                item.latitude(),
                item.longitude(),
                item.publishedAt(),
                item.applicationStatus(),
                item.regionCode()
        );
    }

    private Comparator<RankedItem> resultOrder() {
        return Comparator.comparingInt(RankedItem::rank)
                .thenComparingInt(item -> item.source().type().ordinal())
                .thenComparing(item -> item.source().title())
                .thenComparing(item -> item.source().id());
    }

    private Page preview(List<SearchResultItemResponse> ranked) {
        java.util.Map<SearchType, Integer> counts = new java.util.EnumMap<>(SearchType.class);
        List<SearchResultItemResponse> items = new ArrayList<>();
        for (SearchResultItemResponse item : ranked) {
            int count = counts.getOrDefault(item.type(), 0);
            if (count >= PREVIEW_TYPE_LIMIT) {
                continue;
            }
            counts.put(item.type(), count + 1);
            items.add(item);
            if (items.size() == PREVIEW_TOTAL_LIMIT) {
                break;
            }
        }
        return new Page(items, ranked.size() > items.size());
    }

    private Page page(List<SearchResultItemResponse> ranked, int page, int size) {
        int start = Math.min(page * size, ranked.size());
        int end = Math.min(start + size, ranked.size());
        return new Page(ranked.subList(start, end), end < ranked.size());
    }

    private List<SearchResultItemResponse> itemsOfType(Page page, SearchType type) {
        return page.items().stream().filter(item -> item.type() == type).toList();
    }

    private SearchInput input(IntegratedSearchRequest request) {
        if (request == null) {
            throw new InvalidSearchRequestException("검색 요청이 필요합니다.");
        }
        SearchMatch match;
        try {
            match = SearchMatch.from(request.query());
        } catch (IllegalArgumentException exception) {
            throw new InvalidSearchRequestException(exception.getMessage());
        }
        boolean preview = request.type() == null && (request.preview() == null || request.preview());
        int page = Objects.requireNonNullElse(request.page(), 0);
        if (page < 0 || page > MAX_PAGE) {
            throw new InvalidSearchRequestException("페이지는 0부터 100 사이여야 합니다.");
        }
        int size = requestSize(request);
        Set<RentalType> rentalTypes = immutableSet(request.rentalTypes());
        Set<ApplicationStatus> statuses = immutableSet(request.applicationStatuses());
        return new SearchInput(match, preview, page, size, request.type(), rentalTypes,
                statuses, request.hasActiveAnnouncement());
    }

    private int requestSize(IntegratedSearchRequest request) {
        if (request.type() == null) {
            if (request.size() != null && request.size() != PAGE_SIZE) {
                throw new InvalidSearchRequestException("전체 검색은 페이지당 20개를 제공합니다.");
            }
            return PAGE_SIZE;
        }
        int size = Objects.requireNonNullElse(request.size(), TYPE_PAGE_SIZE);
        if (size < 1 || size > PAGE_SIZE) {
            throw new InvalidSearchRequestException("유형별 검색 크기는 1부터 20 사이여야 합니다.");
        }
        return size;
    }

    private <T> Set<T> immutableSet(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new InvalidSearchRequestException("검색 필터가 올바르지 않습니다.");
        }
        return Set.copyOf(values);
    }

    private record SearchInput(
            SearchMatch match,
            boolean preview,
            int page,
            int size,
            SearchType type,
            Set<RentalType> rentalTypes,
            Set<ApplicationStatus> applicationStatuses,
            Boolean hasActiveAnnouncement
    ) {
        private boolean includes(SearchType requestedType) {
            return type == null || type == requestedType;
        }
    }

    private record Page(List<SearchResultItemResponse> items, boolean hasNext) {
        private Page {
            items = List.copyOf(items);
        }
    }

    private record RankedItem(SearchSourceItem source, int rank) {
    }
}
