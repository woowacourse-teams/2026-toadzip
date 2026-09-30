package com.toadzip.backend.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.toadzip.backend.housing.domain.MapCoordinate;
import com.toadzip.backend.region.repository.RegionCoordinateRepository;
import com.toadzip.backend.region.repository.RegionSearchRepository;
import com.toadzip.backend.region.repository.RegionSearchResult;
import com.toadzip.backend.search.domain.SearchType;
import com.toadzip.backend.search.dto.request.IntegratedSearchRequest;
import com.toadzip.backend.search.dto.response.IntegratedSearchResponse;
import com.toadzip.backend.search.exception.InvalidSearchRequestException;
import com.toadzip.backend.search.repository.InternalSearchRepository;
import com.toadzip.backend.search.repository.SearchSourceItem;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntegratedSearchServiceTest {

    private InternalSearchRepository internalRepository;
    private RegionSearchRepository regionRepository;
    private RegionCoordinateRepository coordinateRepository;
    private IntegratedSearchService service;

    @BeforeEach
    void setUp() {
        internalRepository = mock(InternalSearchRepository.class);
        regionRepository = mock(RegionSearchRepository.class);
        coordinateRepository = mock(RegionCoordinateRepository.class);
        service = new IntegratedSearchService(
                internalRepository,
                regionRepository,
                coordinateRepository,
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

        assertEquals(8, response.announcements().size() + response.complexes().size() + response.regions().size());
        assertTrue(count(response, SearchType.ANNOUNCEMENT) <= 3);
        assertTrue(count(response, SearchType.COMPLEX) <= 3);
        assertTrue(count(response, SearchType.REGION) <= 3);
        assertEquals("서울", response.announcements().getFirst().title());
    }

    @Test
    void 지역_검색만_실패하면_주택_결과와_실패_유형을_함께_반환한다() {
        when(internalRepository.findAnnouncements(any(), anyInt()))
                .thenReturn(List.of(item(SearchType.ANNOUNCEMENT, "1", "서울 공고")));
        when(internalRepository.findComplexes(any(), anyInt())).thenReturn(List.of());
        when(regionRepository.findByKeyword(any())).thenThrow(new IllegalStateException("지역 실패"));

        IntegratedSearchResponse response = service.search(request("서울"));

        assertNull(response.totalCount());
        verify(internalRepository, never()).countAnnouncements(any());
        verify(internalRepository, never()).countComplexes(any());
        assertEquals(1, response.announcements().size());
        assertEquals(1, response.failures().size());
        assertEquals(SearchType.REGION, response.failures().getFirst().type());
    }

    @Test
    void 한_유형에_미리보기_제한보다_결과가_많으면_전체_결과가_있음을_알린다() {
        when(internalRepository.findAnnouncements(any(), anyInt())).thenReturn(List.of());
        List<SearchSourceItem> complexes = List.of(
                item(SearchType.COMPLEX, "1", "서울 단지 1"),
                item(SearchType.COMPLEX, "2", "서울 단지 2"),
                item(SearchType.COMPLEX, "3", "서울 단지 3"),
                item(SearchType.COMPLEX, "4", "서울 단지 4")
        );
        when(internalRepository.findComplexes(any(), anyInt())).thenAnswer(invocation -> {
            int limit = invocation.getArgument(1);
            return complexes.subList(0, Math.min(limit, complexes.size()));
        });

        IntegratedSearchResponse response = service.search(request("서울"));

        assertEquals(3, response.complexes().size());
        assertTrue(response.hasNext());
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

        assertEquals(20, first.announcements().size());
        assertTrue(first.hasNext());
        assertEquals(5, second.announcements().size());
        assertFalse(second.hasNext());
    }

    @Test
    void 과도한_페이지_요청을_거부한다() {
        assertThrows(
                com.toadzip.backend.search.exception.InvalidSearchRequestException.class,
                () -> service.search(fullRequest("서울", Integer.MAX_VALUE))
        );
    }

    @Test
    void 유형별_검색은_선택한_유형만_다섯개씩_조회하고_마지막_페이지를_알린다() {
        List<SearchSourceItem> complexes = java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> item(SearchType.COMPLEX, String.valueOf(index), "서울 단지 " + index))
                .toList();
        when(internalRepository.findComplexes(any(), anyInt())).thenAnswer(invocation -> {
            int limit = invocation.getArgument(1);
            return complexes.subList(0, Math.min(limit, complexes.size()));
        });

        when(internalRepository.countComplexes(any())).thenReturn(7L);

        IntegratedSearchResponse first = service.search(typedRequest("서울", SearchType.COMPLEX, 0, 5));
        IntegratedSearchResponse second = service.search(typedRequest("서울", SearchType.COMPLEX, 1, 5));

        assertEquals(5, first.complexes().size());
        assertEquals(7L, first.totalCount());
        assertEquals(7L, second.totalCount());
        assertEquals(5, first.size());
        assertTrue(first.hasNext());
        assertEquals(2, second.complexes().size());
        assertEquals(1, second.page());
        assertFalse(second.hasNext());
        assertTrue(first.announcements().isEmpty());
        assertTrue(first.regions().isEmpty());
        verify(internalRepository, never()).findAnnouncements(any(), anyInt());
        verify(regionRepository, never()).findByKeyword(any());
    }

    @Test
    void 공고_더보기는_앞_페이지_결과를_중복하거나_누락하지_않는다() {
        List<SearchSourceItem> announcements = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(index -> item(SearchType.ANNOUNCEMENT, String.valueOf(index), "서울 공고 " + (9 - index)))
                .toList();
        when(internalRepository.findAnnouncements(any(), anyInt())).thenAnswer(invocation -> {
            int limit = invocation.getArgument(1);
            return announcements.subList(0, Math.min(limit, announcements.size()));
        });

        when(internalRepository.countAnnouncements(any())).thenReturn(8L);

        IntegratedSearchResponse first = service.search(typedRequest("서울", SearchType.ANNOUNCEMENT, 0, 5));
        IntegratedSearchResponse second = service.search(typedRequest("서울", SearchType.ANNOUNCEMENT, 1, 5));

        assertEquals(8L, first.totalCount());
        assertEquals(8L, second.totalCount());
        List<String> combinedIds = java.util.stream.Stream.concat(
                first.announcements().stream(), second.announcements().stream()
        ).map(result -> result.id()).toList();
        assertEquals(announcements.stream().map(SearchSourceItem::id).toList(), combinedIds);
        verify(internalRepository, never()).findComplexes(any(), anyInt());
        verifyNoInteractions(regionRepository);
    }

    @Test
    void 지역만_검색하면_좌표가_있는_지역과_없는_지역을_함께_반환한다() {
        when(regionRepository.findByKeyword("수원")).thenReturn(List.of(
                region("41110", "경기도 수원시"), region("41111", "경기도 수원시 장안구")
        ));
        when(coordinateRepository.findByRegionCode("41110")).thenReturn(Optional.of(
                new MapCoordinate(new BigDecimal("37.27532584"), new BigDecimal("127.01641895"))
        ));

        IntegratedSearchResponse response = service.search(typedRequest("수원", SearchType.REGION, 0, 5));

        assertEquals(2, response.regions().size());
        assertEquals(new BigDecimal("37.27532584"), response.regions().getFirst().latitude());
        assertEquals(new BigDecimal("127.01641895"), response.regions().getFirst().longitude());
        assertNull(response.regions().getLast().latitude());
        assertNull(response.regions().getLast().longitude());
        verifyNoInteractions(internalRepository);
    }

    @Test
    void 지역_더보기는_유형별_페이지를_적용하고_범위밖_페이지는_비어있다() {
        when(regionRepository.findByKeyword("서울")).thenReturn(java.util.stream.IntStream.rangeClosed(1, 7)
                .mapToObj(index -> region("1110" + index, "서울특별시 구 " + index))
                .toList());

        IntegratedSearchResponse first = service.search(typedRequest("서울", SearchType.REGION, 0, 5));
        IntegratedSearchResponse second = service.search(typedRequest("서울", SearchType.REGION, 1, 5));
        IntegratedSearchResponse beyond = service.search(typedRequest("서울", SearchType.REGION, 2, 5));

        assertEquals(7L, first.totalCount());
        assertEquals(7L, second.totalCount());
        assertEquals(7L, beyond.totalCount());
        assertEquals(5, first.regions().size());
        assertTrue(first.hasNext());
        assertEquals(2, second.regions().size());
        assertFalse(second.hasNext());
        assertTrue(beyond.regions().isEmpty());
        assertFalse(beyond.hasNext());
    }

    @Test
    void 유형별_검색은_크기와_미리보기_옵션이_없어도_다섯개씩_제공한다() {
        when(internalRepository.findComplexes(any(), anyInt())).thenReturn(java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> item(SearchType.COMPLEX, String.valueOf(index), "서울 단지 " + index))
                .toList());

        IntegratedSearchResponse response = service.search(new IntegratedSearchRequest(
                "서울", null, null, null, List.of(), List.of(), null, SearchType.COMPLEX
        ));

        assertEquals(5, response.size());
        assertEquals(5, response.complexes().size());
        assertTrue(response.hasNext());
    }

    @Test
    void 잘못된_유형별_페이지_크기와_기존_전체_검색_크기_변경은_거부한다() {
        assertThrows(InvalidSearchRequestException.class,
                () -> service.search(typedRequest("서울", SearchType.REGION, 0, 0)));
        assertThrows(InvalidSearchRequestException.class,
                () -> service.search(typedRequest("서울", SearchType.REGION, 0, 21)));
        assertThrows(InvalidSearchRequestException.class,
                () -> service.search(typedRequest("서울", null, 0, 5)));
    }

    @Test
    void 선택한_유형이_실패하면_그_유형의_실패만_반환한다() {
        when(internalRepository.findComplexes(any(), anyInt())).thenThrow(new IllegalStateException("검색 실패"));

        IntegratedSearchResponse response = service.search(typedRequest("서울", SearchType.COMPLEX, 0, 5));

        assertTrue(response.complexes().isEmpty());
        assertEquals(List.of(SearchType.COMPLEX), response.failures().stream().map(failure -> failure.type()).toList());
        assertFalse(response.hasNext());
        verify(internalRepository, never()).findAnnouncements(any(), anyInt());
        verifyNoInteractions(regionRepository);
    }

    @Test
    void 유형별_검색의_마지막_허용_페이지는_요청할_수_없는_다음_페이지를_안내하지_않는다() {
        List<SearchSourceItem> complexes = java.util.stream.IntStream.rangeClosed(1, 506)
                .mapToObj(index -> item(SearchType.COMPLEX, String.valueOf(index), "서울 단지 " + index))
                .toList();
        when(internalRepository.findComplexes(any(), anyInt())).thenReturn(complexes);

        IntegratedSearchResponse response = service.search(typedRequest("서울", SearchType.COMPLEX, 100, 5));

        assertEquals(5, response.complexes().size());
        assertEquals("501", response.complexes().getFirst().id());
        assertFalse(response.hasNext());
        assertThrows(InvalidSearchRequestException.class,
                () -> service.search(typedRequest("서울", SearchType.COMPLEX, 101, 5)));
    }

    @Test
    void 검색_건수_집계가_실패해도_검색_결과와_더보기를_유지한다() {
        when(internalRepository.findComplexes(any(), anyInt())).thenReturn(java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> item(SearchType.COMPLEX, String.valueOf(index), "서울 단지 " + index))
                .toList());
        when(internalRepository.countComplexes(any())).thenThrow(new IllegalStateException("집계 실패"));

        IntegratedSearchResponse response = service.search(typedRequest("서울", SearchType.COMPLEX, 0, 5));

        assertEquals(5, response.complexes().size());
        assertTrue(response.hasNext());
        assertTrue(response.failures().isEmpty());
        assertNull(response.totalCount());
    }

    @Test
    void 결과가_없는_유형별_검색은_전체_건수_영을_반환한다() {
        IntegratedSearchResponse response = service.search(typedRequest("빈결과", SearchType.COMPLEX, 0, 5));

        assertEquals(0L, response.totalCount());
        assertFalse(response.hasNext());
    }

    private IntegratedSearchRequest request(String query) {
        return new IntegratedSearchRequest(query, true, 0, 20, List.of(), List.of(), null, null);
    }

    private IntegratedSearchRequest fullRequest(String query, int page) {
        return new IntegratedSearchRequest(query, false, page, 20, List.of(), List.of(), null, null);
    }

    private IntegratedSearchRequest typedRequest(String query, SearchType type, int page, int size) {
        return new IntegratedSearchRequest(query, false, page, size, List.of(), List.of(), null, type);
    }

    private SearchSourceItem item(SearchType type, String id, String title) {
        return new SearchSourceItem(
                type, id, title, "대한민국", "대한민국", null, null, null, null, null
        );
    }

    private RegionSearchResult region(String code, String displayName) {
        return new RegionSearchResult(code, "서울특별시", null, displayName);
    }

    private long count(IntegratedSearchResponse response, SearchType type) {
        return java.util.stream.Stream.of(
                response.announcements(), response.complexes(), response.regions()
        ).flatMap(List::stream).filter(item -> item.type() == type).count();
    }
}
