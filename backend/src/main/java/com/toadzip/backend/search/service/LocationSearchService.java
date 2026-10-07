package com.toadzip.backend.search.service;

import com.toadzip.backend.search.domain.SearchMatch;
import com.toadzip.backend.search.dto.request.LocationSearchRequest;
import com.toadzip.backend.search.dto.response.LocationSearchItemResponse;
import com.toadzip.backend.search.dto.response.LocationSearchResponse;
import com.toadzip.backend.search.exception.InvalidSearchRequestException;
import com.toadzip.backend.search.repository.NaverLocationSearchRepository;
import com.toadzip.backend.search.repository.LocationSearchItem;
import com.toadzip.backend.search.repository.LocationSearchPage;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class LocationSearchService {

    private final NaverLocationSearchRepository repository;

    public LocationSearchService(NaverLocationSearchRepository repository) {
        this.repository = repository;
    }

    public LocationSearchResponse search(LocationSearchRequest request) {
        if (request == null || request.type() == null) {
            throw new InvalidSearchRequestException("위치 검색 유형이 필요합니다.");
        }
        String query = normalizedQuery(request.query());
        int page = Objects.requireNonNullElse(request.page(), 0);
        int size = Objects.requireNonNullElse(request.size(), 5);
        if (page < 0 || page > 100 || size < 1 || size > 15) {
            throw new InvalidSearchRequestException("페이지는 0~100, 검색 크기는 1~15 사이여야 합니다.");
        }
        LocationSearchPage result = repository.search(query, request.type(), page, size);
        return new LocationSearchResponse(result.items().stream().map(this::response).toList(),
                page, size, result.hasNext(), result.totalCount());
    }

    private String normalizedQuery(String query) {
        try {
            return SearchMatch.from(query).normalizedQuery();
        } catch (IllegalArgumentException exception) {
            throw new InvalidSearchRequestException(exception.getMessage());
        }
    }

    private LocationSearchItemResponse response(LocationSearchItem item) {
        return new LocationSearchItemResponse(item.type(), item.id(), item.title(), item.subtitle(),
                item.coordinate().latitude(), item.coordinate().longitude());
    }
}
