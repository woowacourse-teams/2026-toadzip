package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCollectionCheckpoint;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionExecutionLock;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore.BatchProgress;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.LhSourceStore;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementSourceRepository;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementDetailResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementSupplyResponseParser;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LhAnnouncementExternalCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");

    @Mock
    private MyHomeAnnouncementSourceRepository myHomeAnnouncementRepository;

    @Mock
    private LhAnnouncementExternalRepository externalRepository;

    @Mock
    private LhAnnouncementCollectionExecutionLock executionLock;

    @Mock
    private LhSourceStore sourceStore;

    @Mock
    private LhAnnouncementCollectionProgressStore progressStore;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private LhAnnouncementExternalCollectionService service;

    private long nextSourceId;

    @BeforeEach
    void setUp() {
        nextSourceId = 0L;
        lenient().when(executionLock.<ExternalDataCollectionReport>tryRun(any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<ExternalDataCollectionReport> operation = invocation.getArgument(1);
                    return Optional.of(operation.get());
                });
        lenient().when(progressStore.findBatch(any(), any(), any(), any(), any()))
                .thenReturn(BatchProgress.empty());
        LhAnnouncementCollectionProgressManager progressManager =
                new LhAnnouncementCollectionProgressManager(
                        progressStore,
                        failureRecorder,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        Duration.ofHours(6)
                );
        LhAnnouncementPageFetcher pageFetcher = new LhAnnouncementPageFetcher(
                externalRepository,
                new LhAnnouncementDetailResponseParser(),
                new LhAnnouncementSupplyResponseParser(),
                new ExternalDataRetryExecutor(Duration.ZERO, new SimpleMeterRegistry())
        );
        LhAnnouncementCandidateCollector candidateCollector = new LhAnnouncementCandidateCollector(
                pageFetcher,
                sourceStore,
                failureRecorder,
                progressManager,
                new SimpleMeterRegistry()
        );
        service = new LhAnnouncementExternalCollectionService(
                myHomeAnnouncementRepository,
                executionLock,
                progressManager,
                failureRecorder,
                new LhAnnouncementCollectionCandidateResolver(new LhSupplyInfoTypeCodeResolver()),
                candidateCollector
        );
    }

    @Test
    void 서로_다른_공고는_최대_2개씩_동시에_수집하고_모든_결과를_합산한다() throws Exception {
        source(announcementSource("a", "100"), announcementSource("b", "200"),
                announcementSource("c", "300"), announcementSource("d", "400"));
        CountDownLatch firstPairStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            firstPairStarted.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return detailResponse();
            }
            finally {
                active.decrementAndGet();
            }
        });
        when(sourceStore.replaceDetails(any(), any())).thenReturn(1);

        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL));
            try {
                assertThat(firstPairStarted.await(2, TimeUnit.SECONDS)).isTrue();
                verify(externalRepository, times(2)).fetchDetail(any());
            }
            finally {
                release.countDown();
            }
            ExternalDataCollectionReport report = result.get(5, TimeUnit.SECONDS);
            assertThat(peak.get()).isEqualTo(2);
            assertThat(report.storedRowCount()).isEqualTo(4);
            assertThat(report.externalApiCallCount()).isEqualTo(4);
            assertThat(report.failedRequestCount()).isZero();
            verify(progressStore, times(4)).complete(any(), any(), any(), any());
        }
    }

    @Test
    void 호출_제한과_동시에_진행하던_성공_공고는_저장하고_다음_묶음은_호출하지_않는다() throws Exception {
        source(announcementSource("a", "100"), announcementSource("b", "200"),
                announcementSource("c", "300"));
        CountDownLatch firstPairStarted = new CountDownLatch(2);
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
            firstPairStarted.countDown();
            assertThat(firstPairStarted.await(2, TimeUnit.SECONDS)).isTrue();
            if (request.panId().equals("100")) {
                throw ExternalDataRequestException.rateLimited("일일 요청 한도 초과", null, false);
            }
            return detailResponse();
        });
        when(sourceStore.replaceDetails(eq("200"), any())).thenReturn(1);

        ExternalDataCollectionReport report = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        assertThat(report.storedRowCount()).isOne();
        assertThat(report.externalApiCallCount()).isEqualTo(2);
        assertThat(report.rateLimitedRequestCount()).isOne();
        verify(externalRepository, times(2)).fetchDetail(any());
        verify(sourceStore, never()).replaceDetails(eq("100"), any());
        verify(progressStore).complete(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL), eq("b"), any(), eq("200"));
        verify(progressStore, never()).complete(any(), eq("a"), any(), any());
    }

    @Test
    void 같은_panId의_다른_조회_조건은_저장과_체크포인트까지_순서대로_처리한다() {
        MyHomeAnnouncementSource first = announcementSource("a", "100");
        MyHomeAnnouncementSource second = announcementSource("b", "100");
        ReflectionTestUtils.setField(second, "url", second.getUrl().replace("aisTpCd=06", "aisTpCd=07"));
        source(first, announcementSource("c", "200"), second);
        Set<String> stored = ConcurrentHashMap.newKeySet();
        List<String> samePanConditions = new ArrayList<>();
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
            if (request.panId().equals("100")) {
                if (request.announcementTypeCode().equals("07")) {
                    assertThat(stored).contains("100");
                    verify(progressStore).complete(any(), eq("a"), any(), eq("100"));
                }
                samePanConditions.add(request.announcementTypeCode());
            }
            return detailResponse();
        });
        when(sourceStore.replaceDetails(any(), any())).thenAnswer(invocation -> {
            stored.add(invocation.getArgument(0));
            return 1;
        });

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        assertThat(samePanConditions).containsExactly("06", "07");
        assertThat(result.storedRowCount()).isEqualTo(3);
    }

    @Test
    void 저장_예외가_발생해도_진행_중인_다른_공고가_끝난_뒤_원래_예외를_전파한다() throws Exception {
        source(announcementSource("a", "100"), announcementSource("b", "200"),
                announcementSource("c", "300"));
        CountDownLatch failingStoreEntered = new CountDownLatch(1);
        CountDownLatch releaseOtherRequest = new CountDownLatch(1);
        IllegalStateException failure = new IllegalStateException("DB 저장 실패");
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
            if (request.panId().equals("200")) {
                assertThat(releaseOtherRequest.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return detailResponse();
        });
        when(sourceStore.replaceDetails(eq("100"), any())).thenAnswer(invocation -> {
            failingStoreEntered.countDown();
            throw failure;
        });
        when(sourceStore.replaceDetails(eq("200"), any())).thenReturn(1);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL));
            try {
                assertThat(failingStoreEntered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            }
            finally {
                releaseOtherRequest.countDown();
            }
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCause(failure);
        }
        verify(externalRepository, times(2)).fetchDetail(any());
        verify(progressStore).complete(any(), eq("b"), any(), eq("200"));
        verify(progressStore, never()).complete(any(), eq("a"), any(), any());
    }

    @Test
    void 병렬_작업이_둘_다_실패하면_두번째_예외를_suppressed로_보존한다() {
        source(announcementSource("a", "100"), announcementSource("b", "200"));
        IllegalStateException firstFailure = new IllegalStateException("첫 번째 DB 저장 실패");
        IllegalArgumentException secondFailure = new IllegalArgumentException("두 번째 DB 저장 실패");
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenThrow(firstFailure);
        when(sourceStore.replaceDetails(eq("200"), any())).thenThrow(secondFailure);

        assertThatThrownBy(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .isSameAs(firstFailure)
                .satisfies(exception -> assertThat(exception.getSuppressed()).containsExactly(secondFailure));

        verify(externalRepository, times(2)).fetchDetail(any());
        verify(progressStore, never()).complete(any(), any(), any(), any());
    }

    @Test
    void 병렬_요청에도_실행_로그의_MDC를_전달한다() {
        source(announcementSource("a", "100"), announcementSource("b", "200"));
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isEqualTo("request-trace");
            assertThat(MDC.get("executionId")).isEqualTo("collection-155");
            return detailResponse();
        });
        MDC.put("traceId", "request-trace");
        MDC.put("executionId", "collection-155");
        try {
            service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
            assertThat(MDC.get("traceId")).isEqualTo("request-trace");
            assertThat(MDC.get("executionId")).isEqualTo("collection-155");
        }
        finally {
            MDC.clear();
        }
    }

    @Test
    void LH_상세_수집은_상세_행만_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(sourceStore).replaceDetails(eq("100"), any());
        verify(sourceStore, never()).replaceSupplies(any(), any());
        verify(externalRepository).fetchDetail(any());
        verify(externalRepository, never()).fetchSupply(any());
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void LH_상세가_100건을_초과하면_마지막_페이지까지_수집한_뒤_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
            if (request.page() == 1) {
                return detailResponse(0, 100);
            }
            return detailResponse(100, 1);
        });
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(101);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        ArgumentCaptor<LhAnnouncementRequest> requests = ArgumentCaptor.captor();
        ArgumentCaptor<List<LhAnnouncementDetailSource>> sources = ArgumentCaptor.captor();
        verify(externalRepository, times(2)).fetchDetail(requests.capture());
        verify(sourceStore).replaceDetails(eq("100"), sources.capture());
        assertThat(requests.getAllValues()).extracting(LhAnnouncementRequest::page).containsExactly(1, 2);
        assertThat(sources.getValue()).hasSize(101);
        assertThat(sources.getValue()).extracting(LhAnnouncementDetailSource::getSourceOrder)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 101).boxed().toList());
        assertThat(result.storedRowCount()).isEqualTo(101);
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    void LH_상세는_여러_dataset의_합계가_100건을_넘어도_각_dataset이_페이지를_채우지_않으면_종료한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponseWithTwoDatasets(60));
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(120);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository).fetchDetail(any());
        verify(sourceStore).replaceDetails(eq("100"), any());
        assertThat(result.storedRowCount()).isEqualTo(120);
        assertThat(result.externalApiCallCount()).isOne();
    }

    @Test
    void 호출_제한이_발생하면_남은_LH_공고를_조회하지_않는다() {
        source(announcementSource("a", "100"), announcementSource("b", "200"),
                announcementSource("c", "300"));
        when(externalRepository.fetchDetail(any()))
                .thenThrow(ExternalDataRequestException.rateLimited(
                        "resultCode=22, 일일 요청 한도 초과",
                        null,
                        false
                ));

        ExternalDataCollectionReport result = service.collect(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL
        );

        assertThat(result.rateLimitedRequestCount()).isEqualTo(2);
        verify(externalRepository, times(2)).fetchDetail(any());
    }

    @Test
    void LH_공급_수집은_공급_행만_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(sourceStore).replaceSupplies(eq("100"), any());
        verify(sourceStore, never()).replaceDetails(any(), any());
        verify(externalRepository).fetchSupply(any());
        verify(externalRepository, never()).fetchDetail(any());
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void LH_공급이_100건을_초과하면_마지막_페이지까지_수집한_뒤_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
            if (request.page() == 1) {
                return supplyResponse(0, 100);
            }
            return supplyResponse(100, 1);
        });
        when(sourceStore.replaceSupplies(eq("100"), any())).thenReturn(101);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        ArgumentCaptor<LhAnnouncementRequest> requests = ArgumentCaptor.captor();
        ArgumentCaptor<List<LhAnnouncementSupplySource>> sources = ArgumentCaptor.captor();
        verify(externalRepository, times(2)).fetchSupply(requests.capture());
        verify(sourceStore).replaceSupplies(eq("100"), sources.capture());
        assertThat(requests.getAllValues()).extracting(LhAnnouncementRequest::page).containsExactly(1, 2);
        assertThat(sources.getValue()).hasSize(101);
        assertThat(sources.getValue()).extracting(LhAnnouncementSupplySource::getSourceOrder)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 101).boxed().toList());
        assertThat(result.storedRowCount()).isEqualTo(101);
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    void LH_공급의_중간_페이지가_실패하면_기존_원천과_완료_체크포인트를_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
            if (request.page() == 1) {
                return supplyResponse(0, 100);
            }
            throw ExternalDataRequestException.retryable("두 번째 페이지 조회 실패");
        });

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> {
                    assertThat(exception.getRequestDescription()).endsWith("&PG_SZ=100&PAGE=2");
                    assertThat(exception.getAttemptCount()).isEqualTo(3);
                }
        );
        verify(externalRepository, times(4)).fetchSupply(any());
        verify(sourceStore, never()).replaceSupplies(any(), any());
        verify(progressStore, never()).complete(any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.externalApiCallCount()).isEqualTo(4);
    }

    @Test
    void LH_공급_응답이_요청한_페이지_크기를_초과하면_기존_원천을_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse(0, 101));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> assertThat(exception.getRequestDescription()).endsWith("&PG_SZ=100&PAGE=1")
        );
        verify(sourceStore, never()).replaceSupplies(any(), any());
        verify(progressStore, never()).complete(any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void LH_API가_출력시각만_바꾼_동일한_전체_페이지를_반복하면_즉시_실패하고_기존_원천을_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
            if (request.page() == 1) {
                return supplyResponse("20260917010000", 0, 100);
            }
            if (request.page() == 2) {
                return supplyResponse("20260917010001", 0, 100);
            }
            throw new ExternalDataRequestException("세 번째 페이지를 호출하면 안 됩니다.");
        });

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> {
                    assertThat(exception.getRequestDescription()).endsWith("&PG_SZ=100&PAGE=2");
                    assertThat(exception.getMessage()).isEqualTo("LH 공고 API가 동일한 페이지를 반복 응답했습니다.");
                }
        );
        verify(externalRepository, times(2)).fetchSupply(any());
        verify(sourceStore, never()).replaceSupplies(any(), any());
        verify(progressStore, never()).complete(any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.externalApiCallCount()).isEqualTo(2);
    }

    @Test
    void 페이지_실패_기록을_해결하지_못하면_완료_체크포인트를_남기지_않는다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any())).thenReturn(1);
        String pageRequest = announcementRequestDescription() + "&PG_SZ=100&PAGE=1";
        doThrow(new IllegalStateException("실패 기록 갱신 실패"))
                .when(failureRecorder)
                .resolve(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, pageRequest);

        assertThatThrownBy(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("실패 기록 갱신 실패");

        verify(sourceStore).replaceSupplies(eq("100"), any());
        verify(progressStore, never()).complete(any(), any(), any(), any());
    }

    @Test
    void LH_상세_응답에_상세_dataset이_없으면_기존_snapshot과_체크포인트를_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]"));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(sourceStore, never()).replaceDetails(any(), any());
        verify(progressStore, never()).complete(any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void LH_공급_응답에_공급_dataset이_없으면_기존_snapshot과_체크포인트를_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]"));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(sourceStore, never()).replaceSupplies(any(), any());
        verify(progressStore, never()).complete(any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void LH_상세_dataset_타입이_잘못되면_기존_snapshot과_체크포인트를_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any()))
                .thenReturn(response("[{\"dsEtcInfo\":[]},{\"dsSbd\":\"invalid\"}]"));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(sourceStore, never()).replaceDetails(any(), any());
        verify(progressStore, never()).complete(any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void LH_공급_dataset_타입이_잘못되면_기존_snapshot과_체크포인트를_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(response("[{\"dsList01\":1}]"));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(sourceStore, never()).replaceSupplies(any(), any());
        verify(progressStore, never()).complete(any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void LH_상세_저장_실패는_외부_API_실패로_기록하지_않는다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenThrow(new IllegalStateException("DB 저장 실패"));

        assertThatThrownBy(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 저장 실패");

        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        verify(progressStore, never()).complete(any(), any(), any(), any());
    }

    @Test
    void 상세_API_실패는_공급_API_수집을_막지_않는다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenThrow(new ExternalDataRequestException("상세 조회 실패"));
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport detail = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        ExternalDataCollectionReport supply = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        assertThat(detail.failedRequestCount()).isOne();
        assertThat(supply.storedRowCount()).isOne();
    }

    @Test
    void 완료된_동일_요청은_외부_API를_재호출하지_않는다() {
        source(announcementSource());
        when(progressStore.findBatch(eq(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY), any(), any(), any(), any()))
                .thenReturn(progressWithCompletedRequest(announcementRequestDescription()));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(externalRepository, never()).fetchSupply(any());
        verify(sourceStore, never()).replaceSupplies(any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.externalApiCallCount()).isZero();
    }

    @Test
    void 페이지네이션_도입_전_완료_요청은_전체_페이지를_다시_수집한다() {
        source(announcementSource());
        when(progressStore.findBatch(eq(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY), any(), any(), any(), any()))
                .thenReturn(progressWithCompletedRequest(legacyAnnouncementRequestDescription()));
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(externalRepository).fetchSupply(any());
        verify(sourceStore).replaceSupplies(eq("100"), any());
        verify(progressStore).complete(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                "100",
                announcementRequestDescription(),
                "100"
        );
        assertThat(result.externalApiCallCount()).isOne();
    }

    @Test
    void 현재_요청이_완료됐어도_공고_링크가_이전_요청이면_갱신한다() {
        source(announcementSource());
        String currentRequest = announcementRequestDescription();
        String previousRequest = currentRequest + "&PREVIOUS=true";
        when(progressStore.findBatch(eq(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY), any(), any(), any(), any()))
                .thenReturn(new BatchProgress(
                        Set.of(LhAnnouncementCollectionCheckpoint.requestHashOf(currentRequest)),
                        Set.of(),
                        Set.of(),
                        Map.of("100", LhAnnouncementCollectionCheckpoint.requestHashOf(previousRequest))
                ));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(externalRepository, never()).fetchSupply(any());
        verify(progressStore).link(
                ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                "100",
                currentRequest,
                "100"
        );
        assertThat(result.externalApiCallCount()).isZero();
    }

    @Test
    void 실패한_요청은_다음_실행에서_다시_호출한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any()))
                .thenThrow(new ExternalDataRequestException("일시적 실패"))
                .thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport failed = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        ExternalDataCollectionReport retried = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository, org.mockito.Mockito.times(2)).fetchDetail(any());
        verify(progressStore).complete(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                eq("100"),
                any(),
                eq("100")
        );
        assertThat(failed.failedRequestCount()).isOne();
        assertThat(retried.storedRowCount()).isOne();
    }

    @Test
    void 완료_이력이_없는_기존_적재_행도_새_수집_계약으로_다시_호출한다() {
        source(announcementSource());
        when(progressStore.findBatch(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL), any(), any(), any(), any()))
                .thenReturn(new BatchProgress(Set.of(), Set.of("100"), Set.of()));
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository).fetchDetail(any());
        verify(sourceStore).replaceDetails(eq("100"), any());
        verify(progressStore).complete(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                eq("100"),
                any(),
                eq("100")
        );
        assertThat(result.externalApiCallCount()).isOne();
    }

    @Test
    void 수집_이력이_있는_panId의_조회_조건이_바뀌면_다시_호출한다() {
        source(announcementSource());
        when(progressStore.findBatch(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL), any(), any(), any(), any()))
                .thenReturn(new BatchProgress(Set.of(), Set.of("100"), Set.of("100")));
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository).fetchDetail(any());
        assertThat(result.externalApiCallCount()).isOne();
    }

    @Test
    void 완료된_이전_요청과_다른_조회_조건은_다시_호출한다() {
        source(announcementSource());
        String previousRequest = announcementRequestDescription() + "&AIS_TP_CD=05";
        when(progressStore.findBatch(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL), any(), any(), any(), any()))
                .thenReturn(new BatchProgress(
                        Set.of(LhAnnouncementCollectionCheckpoint.requestHashOf(previousRequest)),
                        Set.of(),
                        Set.of()
                ));
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository).fetchDetail(any());
        assertThat(result.externalApiCallCount()).isOne();
    }

    @Test
    void 한_실행에서는_동일한_LH_요청을_한_번만_호출한다() {
        MyHomeAnnouncementSource first = announcementSource("announcement-100");
        MyHomeAnnouncementSource second = announcementSource("announcement-101");
        source(first, second);
        when(externalRepository.fetchSupply(any())).thenThrow(new ExternalDataRequestException("일시적 실패"));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(externalRepository, times(1)).fetchSupply(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 동일한_LH_요청을_공유하는_공고마다_연결을_기록한다() {
        MyHomeAnnouncementSource first = announcementSource("announcement-100");
        MyHomeAnnouncementSource second = announcementSource("announcement-101");
        source(first, second);
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository, times(1)).fetchDetail(any());
        verify(progressStore).complete(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                eq("announcement-100"),
                any(),
                eq("100")
        );
        verify(progressStore).link(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                eq("announcement-101"),
                any(),
                eq("100")
        );
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void 동일한_완료_요청의_공고_링크가_모두_최신이면_다시_저장하지_않는다() {
        MyHomeAnnouncementSource first = announcementSource("announcement-100");
        MyHomeAnnouncementSource second = announcementSource("announcement-101");
        source(first, second);
        String request = announcementRequestDescription();
        String requestHash = LhAnnouncementCollectionCheckpoint.requestHashOf(request);
        when(progressStore.findBatch(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL), any(), any(), any(), any()))
                .thenReturn(new BatchProgress(
                        Set.of(requestHash),
                        Set.of(),
                        Set.of(),
                        Map.of("announcement-100", requestHash, "announcement-101", requestHash)
                ));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository, never()).fetchDetail(any());
        verify(progressStore, never()).complete(any(), any(), any(), any());
        verify(progressStore, never()).link(any(), any(), any(), any());
        assertThat(result.externalApiCallCount()).isZero();
    }

    @Test
    void 동일한_완료_요청을_공유해도_이전_요청을_가리키는_링크만_갱신한다() {
        MyHomeAnnouncementSource first = announcementSource("announcement-100");
        MyHomeAnnouncementSource second = announcementSource("announcement-101");
        source(first, second);
        String currentRequest = announcementRequestDescription();
        String currentRequestHash = LhAnnouncementCollectionCheckpoint.requestHashOf(currentRequest);
        String previousRequestHash = LhAnnouncementCollectionCheckpoint.requestHashOf(
                currentRequest + "&PREVIOUS=true"
        );
        when(progressStore.findBatch(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL), any(), any(), any(), any()))
                .thenReturn(new BatchProgress(
                        Set.of(currentRequestHash),
                        Set.of(),
                        Set.of(),
                        Map.of(
                                "announcement-100", currentRequestHash,
                                "announcement-101", previousRequestHash
                        )
                ));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository, never()).fetchDetail(any());
        verify(progressStore).link(
                ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                "announcement-101",
                currentRequest,
                "100"
        );
        assertThat(result.externalApiCallCount()).isZero();
    }

    @Test
    void 마이홈_공고를_ID_기준_500개씩_조회한다() {
        MyHomeAnnouncementSource source = announcementSource();
        source(source);
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(1);

        service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        org.mockito.ArgumentCaptor<Long> cursor = org.mockito.ArgumentCaptor.captor();
        org.mockito.ArgumentCaptor<Pageable> pageable = org.mockito.ArgumentCaptor.captor();
        verify(myHomeAnnouncementRepository, times(2))
                .findByIdGreaterThanOrderByIdAsc(cursor.capture(), pageable.capture());
        assertThat(cursor.getAllValues()).containsExactly(0L, source.getId());
        assertThat(pageable.getAllValues()).allSatisfy(value -> assertThat(value.getPageSize()).isEqualTo(500));
        verify(progressStore).findBatch(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                any(),
                any(),
                any(),
                eq(NOW.minus(Duration.ofHours(6)))
        );
    }

    @Test
    void LH가_아닌_공급기관은_실패가_아닌_스킵으로_집계한다() {
        source(nonLhAnnouncementSource());

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(failureRecorder).skip(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                any(),
                eq("LH 공급기관이 아닌 마이홈 공고라서 수집 대상이 아닙니다.")
        );
        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        verify(externalRepository, never()).fetchDetail(any());
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.skippedRequestCount()).isOne();
    }

    @Test
    void 지원하지_않는_LH_공고_조건은_실패가_아닌_스킵으로_집계한다() {
        source(unsupportedLhAnnouncementSource());

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(failureRecorder).skip(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                any(),
                eq("LH 공고 조회 조건을 지원하지 않아 건너뛰었습니다.")
        );
        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        verify(externalRepository, never()).fetchDetail(any());
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.skippedRequestCount()).isOne();
    }

    @Test
    void 통합공공임대는_LH_공급정보_코드_064로_호출한다() {
        source(integratedLhAnnouncementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("2015122300020531"), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        ArgumentCaptor<LhAnnouncementRequest> request = ArgumentCaptor.forClass(LhAnnouncementRequest.class);
        verify(externalRepository).fetchDetail(request.capture());
        assertThat(request.getValue().supplyInfoTypeCode()).isEqualTo("064");
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.skippedRequestCount()).isZero();
    }

    @Test
    void 같은_API_수집이_실행_중이면_외부_API를_호출하지_않는다() {
        doReturn(Optional.empty()).when(executionLock).tryRun(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL), any());

        assertThatThrownBy(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .isInstanceOf(IngestAlreadyRunningException.class);

        verify(externalRepository, never()).fetchDetail(any());
    }

    @Test
    void LH_상세나_공급이_아닌_API는_거절한다() {
        assertThatThrownBy(() -> service.collect(ExternalDataSource.MYHOME_ANNOUNCEMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LH 공고 API가 아닙니다.");
    }

    private void source(MyHomeAnnouncementSource... sources) {
        List<MyHomeAnnouncementSource> batch = List.of(sources);
        long lastId = batch.getLast().getId();
        when(myHomeAnnouncementRepository.findByIdGreaterThanOrderByIdAsc(anyLong(), any()))
                .thenAnswer(invocation -> {
                    long cursor = invocation.getArgument(0);
                    if (cursor < lastId) {
                        return batch;
                    }
                    return List.of();
                });
    }

    private MyHomeAnnouncementSource announcementSource() {
        return announcementSource("100");
    }

    private MyHomeAnnouncementSource announcementSource(String pblancId) {
        return announcementSource(pblancId, "100");
    }

    private MyHomeAnnouncementSource announcementSource(String pblancId, String panId) {
        MyHomeAnnouncementSource source = MyHomeAnnouncementSource.from(0, item(
                pblancId,
                "행복주택",
                "https://apply.lh.or.kr/panDetail?panId=" + panId
                        + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06"
        ));
        ReflectionTestUtils.setField(source, "id", ++nextSourceId);
        return source;
    }

    private MyHomeAnnouncementSource nonLhAnnouncementSource() {
        MyHomeAnnouncementSource source = MyHomeAnnouncementSource.from(0, itemWithProvider(
                "100",
                "부산도시공사",
                "행복주택",
                "https://apply.lh.or.kr/panDetail?panId=100"
                        + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06"
        ));
        ReflectionTestUtils.setField(source, "id", ++nextSourceId);
        return source;
    }

    private MyHomeAnnouncementSource unsupportedLhAnnouncementSource() {
        MyHomeAnnouncementSource source = MyHomeAnnouncementSource.from(0, item(
                "100",
                "지원하지 않는 유형",
                "https://apply.lh.or.kr/panDetail?panId=100"
                        + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06"
        ));
        ReflectionTestUtils.setField(source, "id", ++nextSourceId);
        return source;
    }

    private MyHomeAnnouncementSource integratedLhAnnouncementSource() {
        MyHomeAnnouncementSource source = MyHomeAnnouncementSource.from(0, item(
                "2015122300020531",
                "통합공공임대",
                "https://apply.lh.or.kr/panDetail?panId=2015122300020531"
                        + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=48"
        ));
        ReflectionTestUtils.setField(source, "id", ++nextSourceId);
        return source;
    }

    private MyHomeAnnouncementSourceSnapshot item(String supplyType, String url) {
        return item("100", supplyType, url);
    }

    private MyHomeAnnouncementSourceSnapshot item(String pblancId, String supplyType, String url) {
        return new MyHomeAnnouncementSourceSnapshot(
                pblancId, 1, null, "공고", "LH", null, supplyType, null, null, null,
                null, null, null, url, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
    }

    private MyHomeAnnouncementSourceSnapshot itemWithProvider(
            String pblancId,
            String provider,
            String supplyType,
            String url
    ) {
        return new MyHomeAnnouncementSourceSnapshot(
                pblancId, 1, null, "공고", provider, null, supplyType, null, null, null,
                null, null, null, url, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
    }

    private ExternalDataResponse detailResponse() {
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},"
                + "{\"dsEtcInfo\":[{\"CRC_RSN\":\"정정\"}]}]");
    }

    private ExternalDataResponse supplyResponse() {
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},"
                + "{\"dsList01\":[{\"SBD_LGO_NM\":\"행복주택\"}]}]");
    }

    private ExternalDataResponse detailResponse(int start, int count) {
        String rows = java.util.stream.IntStream.range(start, start + count)
                .mapToObj(index -> "{\"LCC_NT_NM\":\"단지-" + index + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsSbd\":[" + rows + "]}]");
    }

    private ExternalDataResponse detailResponseWithTwoDatasets(int countPerDataset) {
        String complexes = java.util.stream.IntStream.range(0, countPerDataset)
                .mapToObj(index -> "{\"LCC_NT_NM\":\"단지-" + index + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        String attachments = java.util.stream.IntStream.range(0, countPerDataset)
                .mapToObj(index -> "{\"CMN_AHFL_NM\":\"첨부-" + index + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsSbd\":[" + complexes
                + "]},{\"dsAhflInfo\":[" + attachments + "]}]");
    }

    private ExternalDataResponse supplyResponse(int start, int count) {
        return supplyResponse(null, start, count);
    }

    private ExternalDataResponse supplyResponse(String responseDateTime, int start, int count) {
        String rows = java.util.stream.IntStream.range(start, start + count)
                .mapToObj(index -> "{\"SBD_LGO_NM\":\"단지-" + index + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        String responseDateTimeField = responseDateTimeField(responseDateTime);
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"" + responseDateTimeField
                + "}]},{\"dsList01\":[" + rows + "]}]");
    }

    private String responseDateTimeField(String responseDateTime) {
        if (responseDateTime == null) {
            return "";
        }
        return ",\"RS_DTTM\":\"" + responseDateTime + "\"";
    }

    private ExternalDataResponse response(String payload) {
        return new ExternalDataResponse(payload, JsonMapper.builder().build().readTree(payload));
    }

    private BatchProgress progressWithCompletedRequest(String requestDescription) {
        return new BatchProgress(
                Set.of(LhAnnouncementCollectionCheckpoint.requestHashOf(requestDescription)),
                Set.of(),
                Set.of()
        );
    }

    private String announcementRequestDescription() {
        return "PAN_ID=100&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06"
                + "&SPL_INF_TP_CD=063&AIS_TP_CD=06&COLLECTION_VERSION=2";
    }

    private String legacyAnnouncementRequestDescription() {
        return "PAN_ID=100&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06"
                + "&SPL_INF_TP_CD=063&AIS_TP_CD=06";
    }
}
