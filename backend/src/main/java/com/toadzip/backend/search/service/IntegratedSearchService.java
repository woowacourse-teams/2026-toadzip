package com.toadzip.backend.search.service;

import com.toadzip.backend.announcement.domain.ApplicationStatus;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.region.repository.RegionSearchRepository;
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
    private static final int MAX_PAGE = 100;

    private final InternalSearchRepository internalSearchRepository;
    private final RegionSearchRepository regionSearchRepository;
    private final Clock clock;

    public IntegratedSearchService(
            InternalSearchRepository internalSearchRepository,
            RegionSearchRepository regionSearchRepository,
            Clock clock
    ) {
        this.internalSearchRepository = internalSearchRepository;
        this.regionSearchRepository = regionSearchRepository;
        this.clock = clock;
    }

    public IntegratedSearchResponse search(IntegratedSearchRequest request) {
        SearchInput input = input(request);
        int fetchLimit = input.preview() ? PREVIEW_TYPE_LIMIT + 1 : (input.page() + 1) * PAGE_SIZE + 1;
        IntegratedSearchCondition condition = new IntegratedSearchCondition(
                input.match(),
                input.rentalTypes(),
                input.applicationStatuses(),
                input.hasActiveAnnouncement(),
                LocalDate.ofInstant(clock.instant(), SEOUL_ZONE)
        );
        List<SearchFailureResponse> failures = new ArrayList<>();
        List<SearchSourceItem> results = new ArrayList<>();
        addAnnouncements(results, failures, condition, fetchLimit);
        addComplexes(results, failures, condition, fetchLimit);
        addRegions(results, failures, input.match());

        List<SearchResultItemResponse> ranked = results.stream()
                .filter(item -> input.match().matches(item.title(), item.subtitle(), item.address()))
                .map(item -> new RankedItem(
                        item,
                        input.match().rank(item.title(), item.subtitle(), item.address())
                ))
                .sorted(resultOrder())
                .map(item -> response(item.source()))
                .toList();
        Page page = input.preview() ? preview(ranked) : page(ranked, input.page());
        return new IntegratedSearchResponse(
                input.match().normalizedQuery(),
                itemsOfType(page, SearchType.ANNOUNCEMENT),
                itemsOfType(page, SearchType.COMPLEX),
                itemsOfType(page, SearchType.REGION),
                failures,
                input.page(),
                input.preview() ? PREVIEW_TOTAL_LIMIT : PAGE_SIZE,
                page.hasNext()
        );
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
                .map(region -> new SearchSourceItem(
                        SearchType.REGION,
                        region.regionCode(),
                        region.displayName(),
                        region.provinceName(),
                        region.displayName(),
                        null,
                        null,
                        null,
                        null,
                        region.regionCode()
                ))
                .toList();
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

    private Page page(List<SearchResultItemResponse> ranked, int page) {
        int start = Math.min(page * PAGE_SIZE, ranked.size());
        int end = Math.min(start + PAGE_SIZE, ranked.size());
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
        boolean preview = request.preview() == null || request.preview();
        int page = request.page() == null ? 0 : request.page();
        if (page < 0 || page > MAX_PAGE) {
            throw new InvalidSearchRequestException("페이지는 0부터 100 사이여야 합니다.");
        }
        if (request.size() != null && request.size() != PAGE_SIZE) {
            throw new InvalidSearchRequestException("전체 검색은 페이지당 20개를 제공합니다.");
        }
        Set<RentalType> rentalTypes = immutableSet(request.rentalTypes());
        Set<ApplicationStatus> statuses = immutableSet(request.applicationStatuses());
        return new SearchInput(match, preview, page, rentalTypes, statuses, request.hasActiveAnnouncement());
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
            Set<RentalType> rentalTypes,
            Set<ApplicationStatus> applicationStatuses,
            Boolean hasActiveAnnouncement
    ) {
    }

    private record Page(List<SearchResultItemResponse> items, boolean hasNext) {
        private Page {
            items = List.copyOf(items);
        }
    }

    private record RankedItem(SearchSourceItem source, int rank) {
    }
}
