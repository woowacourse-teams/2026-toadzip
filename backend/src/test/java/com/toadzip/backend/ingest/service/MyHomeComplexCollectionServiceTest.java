package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.domain.ExternalApiData;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeRegion;
import com.toadzip.backend.ingest.repository.ExternalApiCollectionStore;
import com.toadzip.backend.ingest.repository.MyHomeComplexApiRepository;
import com.toadzip.backend.ingest.repository.MyHomeRegionCatalog;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MyHomeComplexCollectionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private MyHomeComplexApiRepository apiRepository;

    @Mock
    private MyHomeRegionCatalog regionCatalog;

    @Mock
    private ExternalApiCollectionStore store;

    @Mock
    private ExternalApiFailureRecorder failureRecorder;

    private MyHomeComplexCollectionService service;

    @BeforeEach
    void setUp() {
        service = new MyHomeComplexCollectionService(
                CLOCK,
                apiRepository,
                regionCatalog,
                store,
                failureRecorder
        );
    }

    @Test
    @DisplayName("지역의 마지막 페이지까지 조회한 API 데이터를 한 번에 저장한다")
    void storesCompleteRegionPages() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(apiRepository.fetch(region, request(), 1)).thenReturn(response("[{\"id\":1},{\"id\":2}]"));
        when(apiRepository.fetch(region, request(), 2)).thenReturn(response("[{\"id\":3}]"));

        var result = service.collect(request());

        ArgumentCaptor<List<ExternalApiData>> apiData = ArgumentCaptor.captor();
        verify(store).storeApiData(apiData.capture());
        assertThat(apiData.getValue()).extracting(ExternalApiData::getApiData)
                .containsExactly(
                        "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                                + "\"body\":{\"item\":[{\"id\":1},{\"id\":2}]}}}",
                        "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                                + "\"body\":{\"item\":[{\"id\":3}]}}}"
                );
        assertThat(result.storedApiDataCount()).isEqualTo(2);
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    @DisplayName("페이지 조회가 실패하면 불완전한 API 데이터를 저장하지 않는다")
    void doesNotStoreIncompleteRegion() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(apiRepository.fetch(region, request(), 1)).thenReturn(response("[{\"id\":1},{\"id\":2}]"));
        when(apiRepository.fetch(region, request(), 2)).thenThrow(new IllegalStateException("조회 실패"));

        var result = service.collect(request());

        verify(store, never()).storeApiData(any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.storedApiDataCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
    }

    private MyHomeComplexCollectionRequest request() {
        return new MyHomeComplexCollectionRequest("11", "110", 2, 10);
    }

    private ExternalApiResponse response(String items) {
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                + "\"body\":{\"item\":" + items + "}}}";
        return new ExternalApiResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
