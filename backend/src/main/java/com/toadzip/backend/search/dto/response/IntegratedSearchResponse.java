package com.toadzip.backend.search.dto.response;

import java.util.List;

/**
 * @param totalCount 유형별 검색의 전체 건수. 전체 통합 검색 또는 집계 실패 시 null.
 */
public record IntegratedSearchResponse(
        String query,
        List<SearchResultItemResponse> announcements,
        List<SearchResultItemResponse> complexes,
        List<SearchResultItemResponse> regions,
        List<SearchFailureResponse> failures,
        int page,
        int size,
        boolean hasNext,
        Long totalCount
) {
    public IntegratedSearchResponse {
        announcements = List.copyOf(announcements);
        complexes = List.copyOf(complexes);
        regions = List.copyOf(regions);
        failures = List.copyOf(failures);
    }
}
