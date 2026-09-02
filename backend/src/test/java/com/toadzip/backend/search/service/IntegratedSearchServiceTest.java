package com.toadzip.backend.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.toadzip.backend.region.repository.RegionSearchRepository;
import com.toadzip.backend.region.repository.RegionSearchResult;
import com.toadzip.backend.search.domain.SearchType;
import com.toadzip.backend.search.dto.request.IntegratedSearchRequest;
import com.toadzip.backend.search.dto.response.IntegratedSearchResponse;
import com.toadzip.backend.search.repository.InternalSearchRepository;
import com.toadzip.backend.search.repository.SearchSourceItem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntegratedSearchServiceTest {

    private InternalSearchRepository internalRepository;
    private RegionSearchRepository regionRepository;
    private IntegratedSearchService service;

    @BeforeEach
    void setUp() {
        internalRepository = mock(InternalSearchRepository.class);
        regionRepository = mock(RegionSearchRepository.class);
        service = new IntegratedSearchService(
                internalRepository,
                regionRepository,
                Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC)
        );
        when(regionRepository.findByKeyword(any())).thenReturn(List.of());
    }

    @Test
    void 입력중_결과는_전체_여덟개와_유형별_세개를_넘지_않고_일치도순이다() {
        when(internalRepository.findAnnouncements(any(), anyInt())).thenReturn(List.of(
                item(SearchType.ANNOUNCEMENT, "1", "서울"),
                item(SearchType.ANNOUNCEMENT, "2", "서울 행복주택"),
                item(SearchType.ANNOUNCEMENT, "3", "강남 서울주택"),
                item(SearchType.ANNOUNCEMENT, "4", "마포 서울주택")
        ));
        when(internalRepository.findComplexes(any(), anyInt())).thenReturn(List.of(
                item(SearchType.COMPLEX, "1", "서울"),
                item(SearchType.COMPLEX, "2", "서울 단지"),
                item(SearchType.COMPLEX, "3", "강동 서울단지"),
                item(SearchType.COMPLEX, "4", "은평 서울단지")
        ));
        when(regionRepository.findByKeyword("서울")).thenReturn(List.of(
                region("11", "서울특별시"),
                region("11110", "서울특별시 종로구"),
                region("11140", "서울특별시 중구")
        ));

        IntegratedSearchResponse response = service.search(request("서울"));

        assertEquals(8, response.housingInformation().size() + response.locations().size());
        assertTrue(count(response, SearchType.ANNOUNCEMENT) <= 3);
        assertTrue(count(response, SearchType.COMPLEX) <= 3);
        assertTrue(count(response, SearchType.REGION) <= 3);
        assertEquals("서울", response.housingInformation().getFirst().title());
    }

    @Test
    void 지역_검색만_실패하면_주택_결과와_실패_유형을_함께_반환한다() {
        when(internalRepository.findAnnouncements(any(), anyInt()))
                .thenReturn(List.of(item(SearchType.ANNOUNCEMENT, "1", "서울 공고")));
        when(internalRepository.findComplexes(any(), anyInt())).thenReturn(List.of());
        when(regionRepository.findByKeyword(any())).thenThrow(new IllegalStateException("지역 실패"));

        IntegratedSearchResponse response = service.search(request("서울"));

        assertEquals(1, response.housingInformation().size());
        assertEquals(1, response.failures().size());
        assertEquals(SearchType.REGION, response.failures().getFirst().type());
    }

    @Test
    void 전체_결과는_페이지당_스물개를_반환한다() {
        List<SearchSourceItem> announcements = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(index -> item(SearchType.ANNOUNCEMENT, String.valueOf(index), "서울 공고 " + index))
                .toList();
        when(internalRepository.findAnnouncements(any(), anyInt())).thenReturn(announcements);
        when(internalRepository.findComplexes(any(), anyInt())).thenReturn(List.of());

        IntegratedSearchResponse first = service.search(fullRequest("서울", 0));
        IntegratedSearchResponse second = service.search(fullRequest("서울", 1));

        assertEquals(20, first.housingInformation().size());
        assertTrue(first.hasNext());
        assertEquals(5, second.housingInformation().size());
        assertFalse(second.hasNext());
    }

    private IntegratedSearchRequest request(String query) {
        return new IntegratedSearchRequest(query, true, 0, 20, List.of(), List.of(), null);
    }

    private IntegratedSearchRequest fullRequest(String query, int page) {
        return new IntegratedSearchRequest(query, false, page, 20, List.of(), List.of(), null);
    }

    private SearchSourceItem item(SearchType type, String id, String title) {
        return new SearchSourceItem(
                type, id, title, "대한민국", "대한민국", type.name(), null, null, null, null, false, null
        );
    }

    private RegionSearchResult region(String code, String displayName) {
        return new RegionSearchResult(code, "서울특별시", null, displayName);
    }

    private long count(IntegratedSearchResponse response, SearchType type) {
        return java.util.stream.Stream.concat(
                response.housingInformation().stream(),
                response.locations().stream()
        ).filter(item -> item.type() == type).count();
    }
}
