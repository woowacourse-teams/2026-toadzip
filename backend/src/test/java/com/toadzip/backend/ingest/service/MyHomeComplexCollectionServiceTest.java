package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeComplexSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeRegion;
import com.toadzip.backend.ingest.repository.MyHomeComplexExternalRepository;
import com.toadzip.backend.ingest.repository.MyHomeRegionCatalog;
import com.toadzip.backend.ingest.repository.MyHomeSourceStore;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MyHomeComplexCollectionServiceTest {

    @Mock
    private MyHomeComplexExternalRepository externalRepository;

    @Mock
    private MyHomeRegionCatalog regionCatalog;

    @Mock
    private MyHomeSourceStore sourceStore;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private MyHomeComplexCollectionService service;

    @BeforeEach
    void setUp() {
        service = new MyHomeComplexCollectionService(
                JsonMapper.builder().build(),
                externalRepository,
                regionCatalog,
                sourceStore,
                failureRecorder,
                new ExternalDataRetryExecutor(Duration.ZERO)
        );
    }

    @Test
    @DisplayName("지역의 마지막 페이지까지 조회한 API 데이터를 한 번에 저장한다")
    void storesCompleteRegionPages() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response("[{\"hsmpSn\":1},{\"hsmpSn\":2}]"));
        when(externalRepository.fetch(region, request(), 2)).thenReturn(response("[{\"hsmpSn\":3}]"));
        when(sourceStore.replaceComplexRegion(eq(region), any())).thenReturn(3);

        var result = service.collect(request());

        ArgumentCaptor<List<MyHomeComplexSourceItem>> items = ArgumentCaptor.captor();
        verify(sourceStore).replaceComplexRegion(eq(region), items.capture());
        assertThat(items.getValue()).extracting(MyHomeComplexSourceItem::hsmpSn)
                .containsExactly(1L, 2L, 3L);
        assertThat(result.storedRowCount()).isEqualTo(3);
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("페이지 조회가 실패하면 불완전한 API 데이터를 저장하지 않는다")
    void doesNotStoreIncompleteRegion() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response("[{\"hsmpSn\":1},{\"hsmpSn\":2}]"));
        when(externalRepository.fetch(region, request(), 2)).thenThrow(new IllegalStateException("조회 실패"));

        var result = service.collect(request());

        verify(sourceStore, never()).replaceComplexRegion(any(), any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("재시도 가능한 외부 API 실패는 다시 호출하고 실제 호출 횟수를 반환한다")
    void retriesRetryableApiFailureAndReportsCallCount() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenThrow(com.toadzip.backend.ingest.repository.external.ExternalDataRequestException.retryable(
                        "일시적 실패",
                        new IllegalStateException("504")
                ))
                .thenReturn(response("[{\"hsmpSn\":1}]"));
        when(sourceStore.replaceComplexRegion(eq(region), any())).thenReturn(1);

        var result = service.collect(request());

        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.externalApiCallCount()).isEqualTo(2);
        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("재시도를 모두 소진하면 최종 실패만 기록하고 전체 호출 횟수를 반환한다")
    void recordsFailureAfterRetryExhaustion() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenThrow(com.toadzip.backend.ingest.repository.external.ExternalDataRequestException.retryable(
                        "일시적 실패",
                        new IllegalStateException("504")
                ));

        var result = service.collect(request());

        verify(externalRepository, times(3)).fetch(region, request(), 1);
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.externalApiCallCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("API가 요청한 페이지 크기보다 적게 반환해도 totalCount까지 계속 조회한다")
    void continuesUntilTotalCountWhenApiClampsPageSize() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response("[{\"hsmpSn\":1}]", 2));
        when(externalRepository.fetch(region, request(), 2))
                .thenReturn(response("[{\"hsmpSn\":2}]", 2));
        when(sourceStore.replaceComplexRegion(eq(region), any())).thenReturn(2);

        var result = service.collect(request());

        verify(externalRepository).fetch(region, request(), 2);
        assertThat(result.storedRowCount()).isEqualTo(2);
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("재시도를 소진한 실제 실패 페이지와 시도 횟수를 기록한다")
    void recordsActualFailedPageAndAttemptCount() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response("[{\"hsmpSn\":1},{\"hsmpSn\":2}]"));
        when(externalRepository.fetch(region, request(), 2))
                .thenThrow(com.toadzip.backend.ingest.repository.external.ExternalDataRequestException.retryable(
                        "resultCode=05",
                        new IllegalStateException("timeout")
                ));

        service.collect(request());

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> {
                    assertThat(exception.getRequestDescription())
                            .isEqualTo("brtcCode=11&signguCode=110&pageNo=2&numOfRows=2");
                    assertThat(exception.getAttemptCount()).isEqualTo(3);
                }
        );
    }

    @Test
    @DisplayName("정상 코드에 body와 totalCount가 없으면 기존 지역 snapshot을 교체하지 않는다")
    void doesNotReplaceRegionWhenSuccessfulResponseHasNoBody() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1)).thenReturn(responseWithoutBody());

        var result = service.collect(request());

        verify(sourceStore, never()).replaceComplexRegion(any(), any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("totalCount가 0인 응답은 명시적인 빈 snapshot으로 저장한다")
    void replacesRegionWithExplicitEmptySnapshot() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1)).thenReturn(response("[]", 0));
        when(sourceStore.replaceComplexRegion(region, List.of())).thenReturn(0);

        var result = service.collect(request());

        verify(sourceStore).replaceComplexRegion(region, List.of());
        assertThat(result.failedRequestCount()).isZero();
    }

    private MyHomeComplexCollectionRequest request() {
        return new MyHomeComplexCollectionRequest("11", "110", 2, 10);
    }

    private ExternalDataResponse response(String items) {
        return response(items, null);
    }

    private ExternalDataResponse response(String items, Integer totalCount) {
        String totalCountField = totalCount == null ? "" : "\"totalCount\":" + totalCount + ",";
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                + "\"body\":{" + totalCountField + "\"item\":" + items + "}}}";
        return new ExternalDataResponse(payload, JsonMapper.builder().build().readTree(payload));
    }

    private ExternalDataResponse responseWithoutBody() {
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"}}}";
        return new ExternalDataResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
