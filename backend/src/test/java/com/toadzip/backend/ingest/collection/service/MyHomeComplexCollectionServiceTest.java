package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeComplexExternalRepository;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeRegion;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.api.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeRegionCatalog;
import com.toadzip.backend.ingest.collection.myhome.complex.service.MyHomeComplexCollectionService;
import com.toadzip.backend.ingest.collection.myhome.complex.service.MyHomeComplexRegionCollectionService;
import com.toadzip.backend.ingest.collection.myhome.complex.service.MyHomeComplexStorageService;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MyHomeComplexCollectionServiceTest {

    @Mock
    private MyHomeComplexExternalRepository externalRepository;

    @Mock
    private MyHomeRegionCatalog regionCatalog;

    @Mock
    private MyHomeComplexStorageService storage;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private MyHomeComplexCollectionService service;

    @BeforeEach
    void setUp() {
        var regionCollector = new CollectionServiceTestFixture().complex(externalRepository, regionCatalog,
                storage, failureRecorder, new ExternalDataRetryExecutor(Duration.ZERO, new SimpleMeterRegistry()));
        service = new MyHomeComplexCollectionService(regionCatalog, regionCollector, CollectionServiceTestFixture.lock());
    }

    @Test
    @DisplayName("지역의 마지막 페이지까지 조회한 API 데이터를 한 번에 저장한다")
    void storesCompleteRegionPages() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response(itemsFor(region, 1, 2), 3));
        when(externalRepository.fetch(region, request(), 2)).thenReturn(response(itemsFor(region, 3), 3));

        var result = service.collect(request());

        ArgumentCaptor<MyHomeComplexCollectedResponse> snapshots = ArgumentCaptor.captor();
        verify(storage).complete(any(), argThat(r -> r.provinceCode().equals(region.provinceCode())
                        && r.districtCode().equals(region.districtCode())), snapshots.capture());
        assertThat(snapshots.getValue().rows()).extracting(com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeComplexSourceSnapshot::hsmpSn)
                .containsExactly(1L, 2L, 3L);
        assertThat(result.storedRowCount()).isEqualTo(3);
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.externalApiCallCount()).isEqualTo(2);
        verify(failureRecorder).resolveStartingWith(
                ExternalDataSource.MYHOME_COMPLEX, "brtcCode=11&signguCode=110&pageNo="
        );
    }

    @Test
    @DisplayName("페이지 조회가 실패하면 불완전한 API 데이터를 저장하지 않는다")
    void doesNotStoreIncompleteRegion() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response(itemsFor(region, 1, 2), 3));
        when(externalRepository.fetch(region, request(), 2)).thenThrow(new ExternalDataRequestException("조회 실패"));

        var result = service.collect(request());

        verify(storage, never()).complete(any(), any(), any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("후속 페이지의 totalCount가 바뀌면 기존 지역 원천을 교체하지 않는다")
    void doesNotReplaceRegionWhenTotalCountChangesBetweenPages() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response(itemsFor(region, 1, 2), 4));
        when(externalRepository.fetch(region, request(), 2))
                .thenReturn(response(itemsFor(region, 3), 3));

        MyHomeComplexCollectionReport result = service.collect(request());

        verify(storage, never()).complete(any(), any(), any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("totalCount 없는 짧은 첫 페이지는 기존 지역 원천을 교체하지 않는다")
    void doesNotReplaceRegionWhenTotalCountIsMissing() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(responseWithoutTotalCount(itemsFor(region, 1)));

        MyHomeComplexCollectionReport result = service.collect(request());

        verify(storage, never()).complete(any(), any(), any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.storedRowCount()).isZero();
    }

    @Test
    @DisplayName("페이지 간 같은 원천 키의 내용이 다르면 기존 지역 원천을 교체하지 않는다")
    void doesNotReplaceRegionWhenSourceContentsConflictBetweenPages() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response(itemsFor(region, 1, 2), 4));
        when(externalRepository.fetch(region, request(), 2))
                .thenReturn(response("""
                        [{"hsmpSn":2,"brtcCode":"11","signguCode":"110","bassMtRntchrg":100000},
                         {"hsmpSn":3,"brtcCode":"11","signguCode":"110"}]
                        """, 4));

        MyHomeComplexCollectionReport result = service.collect(request());

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> assertThat(exception.getRequestDescription())
                        .isEqualTo("brtcCode=11&signguCode=110&pageNo=2&numOfRows=2")
        );
        verify(storage, never()).complete(any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.storedRowCount()).isZero();
    }

    @Test
    void 페이지_간_동일_원천_반복은_허용하고_원본_행_수로_마지막_페이지를_판정한다() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response(itemsFor(region, 1, 2), 4));
        when(externalRepository.fetch(region, request(), 2))
                .thenReturn(response(itemsFor(region, 2, 3), 4));

        var result = service.collect(request());

        verify(storage).complete(any(), argThat(r -> r.provinceCode().equals(region.provinceCode())
                        && r.districtCode().equals(region.districtCode())), any());
        verify(externalRepository, never()).fetch(region, request(), 3);
        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.storedRowCount()).isEqualTo(4);
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 페이지의 동일 원천 반복은 허용하고 원본 행 모두의 저장 건수를 보고한다")
    void storesIdenticalSourcesRepeatedWithinPage() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response(itemsFor(region, 1, 1), 2));

        MyHomeComplexCollectionReport result = service.collect(request());

        verify(storage).complete(any(), argThat(r -> r.provinceCode().equals(region.provinceCode())
                        && r.districtCode().equals(region.districtCode())), any());
        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.storedRowCount()).isEqualTo(2);
    }

    @Test
    void 저장_정규화_뒤_내용이_같은_원천은_동일한_행으로_취급한다() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1)).thenReturn(response("""
                [{"hsmpSn":1,"brtcCode":"11","signguCode":"110","styleNm":"C",\
                  "rnAdres":"서울 주소","suplyPrvuseAr":22.03},
                 {"hsmpSn":1,"brtcCode":"11","signguCode":"110","styleNm":"C ",\
                  "rnAdres":" 서울 주소 ","suplyPrvuseAr":22.0300}]
                """, 2));

        var result = service.collect(request());

        verify(storage).complete(any(), argThat(r -> r.provinceCode().equals(region.provinceCode())
                        && r.districtCode().equals(region.districtCode())), any());
        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.storedRowCount()).isEqualTo(2);
    }

    @Test
    void 한_페이지의_같은_원천_키에_다른_금액이_있으면_지역을_교체하지_않는다() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1)).thenReturn(response("""
                [{"hsmpSn":1,"brtcCode":"11","signguCode":"110","bassMtRntchrg":100000},
                 {"hsmpSn":1,"brtcCode":"11","signguCode":"110","bassMtRntchrg":200000}]
                """, 2));

        var result = service.collect(request());

        verify(storage, never()).complete(any(), any(), any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("식별자 없는 단지 행은 지역 원천 교체 전에 수집 실패로 처리한다")
    void doesNotReplaceRegionWhenComplexIdentifierIsMissing() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response("[{\"brtcCode\":\"11\",\"signguCode\":\"110\"}]", 1));

        MyHomeComplexCollectionReport result = service.collect(request());

        verify(storage, never()).complete(any(), any(), any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.externalApiCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("지역 코드가 없으면 기존 지역 원천을 교체하지 않고 실패를 기록한다")
    void recordsMissingRegionCodeAsPageFailure() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response("[{\"hsmpSn\":1}]", 1));

        MyHomeComplexCollectionReport result = service.collect(request());

        verify(storage, never()).complete(any(), any(), any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.storedRowCount()).isZero();
    }

    @Test
    @DisplayName("오류 코드와 빈 body가 함께 오면 기존 지역 원천을 교체하지 않는다")
    void preservesRegionSourcesWhenErrorCodeHasEmptyBody() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"99\"},"
                + "\"body\":{\"totalCount\":0,\"item\":[]}}}";
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(JsonMapper.builder().build().readTree(payload));

        MyHomeComplexCollectionReport result = service.collect(request());

        verify(storage, never()).complete(any(), any(), any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.storedRowCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"26,110", "11,111"})
    @DisplayName("후속 페이지에 다른 지역 코드가 있으면 실제 페이지를 실패로 기록한다")
    void recordsMismatchedRegionCodeOnActualPage(String responseProvinceCode, String responseDistrictCode) {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response(itemsFor(region, 1), 2));
        String mismatchedItem = "[{\"hsmpSn\":2,\"brtcCode\":\"" + responseProvinceCode
                + "\",\"signguCode\":\"" + responseDistrictCode + "\"}]";
        when(externalRepository.fetch(region, request(), 2))
                .thenReturn(response(mismatchedItem, 2));

        MyHomeComplexCollectionReport result = service.collect(request());

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> {
                    assertThat(exception.getRequestDescription())
                            .isEqualTo("brtcCode=11&signguCode=110&pageNo=2&numOfRows=2");
                    assertThat(exception.getAttemptCount()).isOne();
                    assertThat(exception.getCause())
                            .hasMessage("응답 행의 지역이 요청한 지역과 다릅니다.");
                }
        );
        verify(storage, never()).complete(any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("후속 페이지의 데이터 없음 응답은 지역 원천을 교체하지 않는다")
    void doesNotReplaceRegionAfterNoDataOnFollowingPage() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response(itemsFor(region, 1, 2), 3));
        when(externalRepository.fetch(region, request(), 2)).thenReturn(noDataResponse());

        var result = service.collect(request());

        verify(storage, never()).complete(any(), any(), any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("재시도 가능한 외부 API 실패는 다시 호출하고 실제 호출 횟수를 반환한다")
    void retriesRetryableApiFailureAndReportsCallCount() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenThrow(ExternalDataRequestException.retryable(
                        "일시적 실패",
                        new IllegalStateException("504")
                ))
                .thenReturn(response(itemsFor(region, 1), 1));

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
                .thenThrow(ExternalDataRequestException.retryable(
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
                .thenReturn(response(itemsFor(region, 1), 2));
        when(externalRepository.fetch(region, request(), 2))
                .thenReturn(response(itemsFor(region, 2), 2));

        var result = service.collect(request());

        verify(externalRepository).fetch(region, request(), 2);
        assertThat(result.storedRowCount()).isEqualTo(2);
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("문자열 totalCount 응답도 정상 snapshot으로 저장한다")
    void acceptsTextualTotalCount() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(responseWithTextualTotalCount(itemsFor(region, 1), "1"));

        var result = service.collect(request());

        verify(storage).complete(any(), argThat(r -> r.provinceCode().equals(region.provinceCode())
                        && r.districtCode().equals(region.districtCode())), any());
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.externalApiCallCount()).isOne();
    }

    @Test
    @DisplayName("재시도를 소진한 실제 실패 페이지와 시도 횟수를 기록한다")
    void recordsActualFailedPageAndAttemptCount() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response(itemsFor(region, 1, 2), 3));
        when(externalRepository.fetch(region, request(), 2))
                .thenThrow(ExternalDataRequestException.retryable(
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
    @DisplayName("후속 페이지 파싱 실패는 실제 페이지와 시도 횟수로 기록하고 성공 처리하지 않는다")
    void recordsActualComplexParseFailurePageAndAttemptCount() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        MyHomeComplexCollectionRequest request = request();
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request, 1))
                .thenReturn(response(itemsFor(region, 1, 2), 3));
        when(externalRepository.fetch(region, request, 2))
                .thenReturn(response("[{\"hsmpSn\":{}}]", 3));

        MyHomeComplexCollectionReport result = service.collect(request);

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> {
                    assertThat(exception.getRequestDescription())
                            .isEqualTo("brtcCode=11&signguCode=110&pageNo=2&numOfRows=2");
                    assertThat(exception.getAttemptCount()).isOne();
                }
        );
        verify(failureRecorder, never()).resolve(
                ExternalDataSource.MYHOME_COMPLEX,
                "brtcCode=11&signguCode=110&pageNo=2&numOfRows=2"
        );
        verify(storage, never()).complete(any(), any(), any());
        verify(failureRecorder, never()).resolveStartingWith(any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("정상 코드에 body와 totalCount가 없으면 기존 지역 snapshot을 교체하지 않는다")
    void doesNotReplaceRegionWhenSuccessfulResponseHasNoBody() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1)).thenReturn(responseWithoutBody());

        var result = service.collect(request());

        verify(storage, never()).complete(any(), any(), any());
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

        var result = service.collect(request());

        verify(storage).complete(any(), argThat(r -> r.provinceCode().equals(region.provinceCode())
                        && r.districtCode().equals(region.districtCode())), argThat(response -> response.rows().isEmpty()));
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    @DisplayName("응답 항목 변환 실패도 실제 페이지와 시도 횟수로 기록한다")
    void recordsItemMappingFailureAsPageFailure() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1))
                .thenReturn(response("[{\"hsmpSn\":{}}]", 1));

        service.collect(request());

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> {
                    assertThat(exception.getRequestDescription())
                            .isEqualTo("brtcCode=11&signguCode=110&pageNo=1&numOfRows=2");
                    assertThat(exception.getAttemptCount()).isOne();
                    assertThat(exception.getCause())
                            .hasMessage("마이홈 단지 응답 행 형식이 올바르지 않습니다.");
                }
        );
        verify(externalRepository).fetch(region, request(), 1);
    }

    @Test
    @DisplayName("마이홈 단지 저장 실패는 외부 API 실패로 기록하지 않는다")
    void propagatesComplexStoreFailure() {
        MyHomeRegion region = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        when(regionCatalog.find("11", "110")).thenReturn(region);
        when(externalRepository.fetch(region, request(), 1)).thenReturn(response(itemsFor(region, 1), 1));
        doThrow(new IllegalStateException("DB 저장 실패")).when(storage).complete(any(), argThat(r -> r.provinceCode().equals(region.provinceCode())
                        && r.districtCode().equals(region.districtCode())), any());

        assertThatThrownBy(() -> service.collect(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 저장 실패");

        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        verify(failureRecorder, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("전국 동시 수집의 저장 실패도 외부 API 실패로 기록하지 않는다")
    void propagatesConcurrentComplexStoreFailure() {
        MyHomeRegion seoul = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        MyHomeRegion busan = new MyHomeRegion("26", "110", "부산광역시", "중구");
        MyHomeComplexCollectionRequest request = MyHomeComplexCollectionRequest.allRegions(2, 10);
        when(regionCatalog.findAll()).thenReturn(List.of(busan, seoul));
        when(externalRepository.fetch(seoul, request, 1)).thenReturn(response(itemsFor(seoul, 1), 1));
        when(externalRepository.fetch(busan, request, 1)).thenReturn(response(itemsFor(busan, 2), 1));
        doThrow(new IllegalStateException("DB 저장 실패")).when(storage).complete(any(), argThat(r -> r.provinceCode().equals(seoul.provinceCode())
                        && r.districtCode().equals(seoul.districtCode())), any());

        assertThatThrownBy(() -> service.collect(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 저장 실패");

        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("전국 동시 수집 worker에 실행 ID를 전파한다")
    void propagatesExecutionIdToConcurrentWorkers() {
        MyHomeRegion seoul = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        MyHomeRegion busan = new MyHomeRegion("26", "110", "부산광역시", "중구");
        MyHomeComplexCollectionRequest request = MyHomeComplexCollectionRequest.allRegions(2, 10);
        MyHomeComplexRegionCollectionService concurrentCollector = mock(MyHomeComplexRegionCollectionService.class);
        MyHomeComplexCollectionService concurrentService = new MyHomeComplexCollectionService(
                regionCatalog,
                concurrentCollector, CollectionServiceTestFixture.lock()
        );
        String executionId = UUID.randomUUID().toString();
        when(regionCatalog.findAll()).thenReturn(List.of(seoul, busan));
        when(concurrentCollector.collect(any(), eq(request), any(AtomicBoolean.class)))
                .thenAnswer(invocation -> {
                    assertThat(MDC.get("executionId")).isEqualTo(executionId);
                    return MyHomeComplexCollectionReport.empty();
                });

        MDC.put("executionId", executionId);
        try {
            concurrentService.collect(request);
        }
        finally {
            MDC.clear();
        }

        verify(concurrentCollector, times(2))
                .collect(any(), eq(request), any(AtomicBoolean.class));
    }

    @Test
    @DisplayName("전국 수집 중 호출 제한이 발생하면 재시도와 대기 지역 요청을 중단한다")
    void stopsConcurrentRegionsAfterRateLimit() {
        List<MyHomeRegion> regions = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(index -> new MyHomeRegion(
                        "11",
                        "%03d".formatted(index),
                        "서울특별시",
                        "테스트구" + index
                ))
                .toList();
        MyHomeComplexCollectionRequest request =
                MyHomeComplexCollectionRequest.allRegions(2, 10);
        when(regionCatalog.findAll()).thenReturn(regions);
        when(externalRepository.fetch(any(), eq(request), eq(1)))
                .thenThrow(ExternalDataRequestException.rateLimited(
                        "resultCode=23, 초당 요청 한도 초과",
                        null,
                        true
                ));

        var result = service.collect(request);

        assertThat(result.rateLimitedRequestCount()).isBetween(1, 4);
        assertThat(result.failedRequestCount()).isEqualTo(result.rateLimitedRequestCount());
        verify(externalRepository, atMost(4)).fetch(any(), eq(request), eq(1));
        verify(storage, never()).complete(any(), any(), any());
    }

    @Test
    @DisplayName("호출 제한 취소 후 실행 중인 worker 종료를 기다리고 완료 결과를 집계한다")
    void waitsForRunningWorkerAndReportsItsCompletedResultAfterRateLimit() throws Exception {
        MyHomeRegion rateLimitedRegion = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        MyHomeRegion slowRegion = new MyHomeRegion("26", "110", "부산광역시", "중구");
        MyHomeComplexCollectionRequest request = MyHomeComplexCollectionRequest.allRegions(2, 10);
        MyHomeComplexRegionCollectionService concurrentCollector = mock(MyHomeComplexRegionCollectionService.class);
        MyHomeComplexCollectionService concurrentService = new MyHomeComplexCollectionService(
                regionCatalog,
                concurrentCollector, CollectionServiceTestFixture.lock()
        );
        CountDownLatch slowWorkerStarted = new CountDownLatch(1);
        CountDownLatch rateLimitReturned = new CountDownLatch(1);
        CountDownLatch slowWorkerInterrupted = new CountDownLatch(1);
        CountDownLatch allowSlowWorkerToFinish = new CountDownLatch(1);
        when(regionCatalog.findAll()).thenReturn(List.of(rateLimitedRegion, slowRegion));
        when(concurrentCollector.collect(eq(rateLimitedRegion), eq(request), any(AtomicBoolean.class)))
                .thenAnswer(invocation -> {
                    slowWorkerStarted.await(2, TimeUnit.SECONDS);
                    rateLimitReturned.countDown();
                    return new MyHomeComplexCollectionReport(
                            ExternalDataSource.MYHOME_COMPLEX.operation(),
                            0,
                            1,
                            1,
                            1
                    );
                });
        when(concurrentCollector.collect(eq(slowRegion), eq(request), any(AtomicBoolean.class)))
                .thenAnswer(invocation -> {
                    slowWorkerStarted.countDown();
                    awaitIgnoringInterrupt(allowSlowWorkerToFinish, slowWorkerInterrupted);
                    return new MyHomeComplexCollectionReport(
                            ExternalDataSource.MYHOME_COMPLEX.operation(),
                            3,
                            0,
                            1
                    );
                });
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try {
            Future<MyHomeComplexCollectionReport> collection = caller.submit(
                    () -> concurrentService.collect(request)
            );

            assertThat(rateLimitReturned.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(slowWorkerInterrupted.getCount()).isOne();
            assertThat(collection.isDone()).isFalse();
            allowSlowWorkerToFinish.countDown();

            MyHomeComplexCollectionReport report = collection.get(2, TimeUnit.SECONDS);
            assertThat(report.storedRowCount()).isEqualTo(3);
            assertThat(report.failedRequestCount()).isOne();
            assertThat(report.rateLimitedRequestCount()).isOne();
            assertThat(report.externalApiCallCount()).isEqualTo(2);
        }
        finally {
            allowSlowWorkerToFinish.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    @DisplayName("호출 제한으로 재시도 대기 worker를 취소해도 수집 실패로 바꾸지 않는다")
    void cancelsRetryWaitAfterRateLimitWithoutAddingFailure() throws Exception {
        MyHomeRegion retryingRegion = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        MyHomeRegion rateLimitedRegion = new MyHomeRegion("26", "110", "부산광역시", "중구");
        MyHomeComplexCollectionRequest request = MyHomeComplexCollectionRequest.allRegions(2, 10);
        var retryingCollector = new CollectionServiceTestFixture().complex(externalRepository, regionCatalog,
                storage, failureRecorder, new ExternalDataRetryExecutor(Duration.ofSeconds(30), new SimpleMeterRegistry()));
        MyHomeComplexCollectionService concurrentService = new MyHomeComplexCollectionService(
                regionCatalog, retryingCollector, CollectionServiceTestFixture.lock());
        CountDownLatch retryRequestStarted = new CountDownLatch(1);
        when(regionCatalog.findAll()).thenReturn(List.of(retryingRegion, rateLimitedRegion));
        when(externalRepository.fetch(retryingRegion, request, 1))
                .thenAnswer(invocation -> {
                    retryRequestStarted.countDown();
                    throw ExternalDataRequestException.retryable(
                            "일시적 실패",
                            new IllegalStateException("504")
                    );
                });
        when(externalRepository.fetch(rateLimitedRegion, request, 1))
                .thenAnswer(invocation -> {
                    retryRequestStarted.await(2, TimeUnit.SECONDS);
                    throw ExternalDataRequestException.rateLimited(
                            "resultCode=23, 초당 요청 한도 초과",
                            null,
                            true
                    );
                });

        MyHomeComplexCollectionReport report = concurrentService.collect(request);

        assertThat(report.rateLimitedRequestCount()).isOne();
        assertThat(report.failedRequestCount()).isOne();
        assertThat(report.externalApiCallCount()).isEqualTo(2);
        verify(storage, never()).complete(any(), any(), any());
    }

    @Test
    @DisplayName("worker 예외 시 다른 worker를 취소하고 실제 종료된 뒤 예외를 전파한다")
    void waitsForRunningWorkerBeforePropagatingWorkerFailure() throws Exception {
        MyHomeRegion failedRegion = new MyHomeRegion("11", "110", "서울특별시", "종로구");
        MyHomeRegion slowRegion = new MyHomeRegion("26", "110", "부산광역시", "중구");
        MyHomeComplexCollectionRequest request = MyHomeComplexCollectionRequest.allRegions(2, 10);
        MyHomeComplexRegionCollectionService concurrentCollector = mock(MyHomeComplexRegionCollectionService.class);
        MyHomeComplexCollectionService concurrentService = new MyHomeComplexCollectionService(
                regionCatalog,
                concurrentCollector, CollectionServiceTestFixture.lock()
        );
        CountDownLatch slowWorkerStarted = new CountDownLatch(1);
        CountDownLatch slowWorkerInterrupted = new CountDownLatch(1);
        CountDownLatch allowSlowWorkerToFinish = new CountDownLatch(1);
        AtomicReference<Thread> serviceThread = new AtomicReference<>();
        when(regionCatalog.findAll()).thenReturn(List.of(failedRegion, slowRegion));
        when(concurrentCollector.collect(eq(failedRegion), eq(request), any(AtomicBoolean.class)))
                .thenAnswer(invocation -> {
                    slowWorkerStarted.await(2, TimeUnit.SECONDS);
                    throw new IllegalStateException("DB 저장 실패");
                });
        when(concurrentCollector.collect(eq(slowRegion), eq(request), any(AtomicBoolean.class)))
                .thenAnswer(invocation -> {
                    slowWorkerStarted.countDown();
                    awaitIgnoringInterrupt(allowSlowWorkerToFinish, slowWorkerInterrupted);
                    return MyHomeComplexCollectionReport.empty();
                });
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try {
            Future<MyHomeComplexCollectionReport> collection = caller.submit(() -> {
                serviceThread.set(Thread.currentThread());
                return concurrentService.collect(request);
            });

            assertThat(slowWorkerInterrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(collection.isDone()).isFalse();
            serviceThread.get().interrupt();
            assertThat(awaitInterruptConsumed(serviceThread.get())).isTrue();
            allowSlowWorkerToFinish.countDown();

            assertThatThrownBy(() -> collection.get(2, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception -> {
                        assertThat(exception.getCause())
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage("DB 저장 실패");
                        assertThat(exception.getCause().getSuppressed())
                                .singleElement()
                                .satisfies(suppressed -> assertThat(suppressed)
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessage("마이홈 단지 수집이 중단되었습니다."));
                    });
        }
        finally {
            allowSlowWorkerToFinish.countDown();
            caller.shutdownNow();
        }
    }

    private MyHomeComplexCollectionRequest request() {
        return new MyHomeComplexCollectionRequest("11", "110", 2, 10);
    }

    private void awaitIgnoringInterrupt(CountDownLatch release, CountDownLatch interrupted) {
        boolean restoreInterrupt = false;
        while (true) {
            try {
                release.await();
                break;
            }
            catch (InterruptedException exception) {
                interrupted.countDown();
                restoreInterrupt = true;
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean awaitInterruptConsumed(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isInterrupted() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return !thread.isInterrupted();
    }

    private JsonNode responseWithoutTotalCount(String items) {
        return response(items, null);
    }

    private String itemsFor(MyHomeRegion region, long... ids) {
        return LongStream.of(ids)
                .mapToObj(id -> "{\"hsmpSn\":" + id
                        + ",\"brtcCode\":\"" + region.provinceCode()
                        + "\",\"signguCode\":\"" + region.districtCode() + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private JsonNode response(String items, Integer totalCount) {
        String totalCountField = "";
        if (totalCount != null) {
            totalCountField = "\"totalCount\":" + totalCount + ",";
        }
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                + "\"body\":{" + totalCountField + "\"item\":" + items + "}}}";
        return JsonMapper.builder().build().readTree(payload);
    }

    private JsonNode responseWithTextualTotalCount(String items, String totalCount) {
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                + "\"body\":{\"totalCount\":\"" + totalCount + "\",\"item\":" + items + "}}}";
        return JsonMapper.builder().build().readTree(payload);
    }

    private JsonNode responseWithoutBody() {
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"}}}";
        return JsonMapper.builder().build().readTree(payload);
    }

    private JsonNode noDataResponse() {
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"03\"}}}";
        return JsonMapper.builder().build().readTree(payload);
    }
}
