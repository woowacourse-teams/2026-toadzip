package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.fixture.LhSourceViews;
import com.toadzip.backend.ingest.collection.fixture.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.LhStorageFixtures;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.domain.LhAnnouncementCatalogSourceView;
import com.toadzip.backend.ingest.lh.collection.announcementcatalog.repository.LhAnnouncementCatalogSourceReader;
import com.toadzip.backend.ingest.lh.collection.configuration.LhAnnouncementClientProperties;
import com.toadzip.backend.ingest.lh.collection.detail.domain.LhAnnouncementDetailSourceView;
import com.toadzip.backend.ingest.lh.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.lh.collection.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.lh.collection.repository.external.LhAnnouncementCircuitBreaker;
import com.toadzip.backend.ingest.lh.collection.service.EmptyLhDetailReplacementException;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCandidateCollector;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionPolicy;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionProgressManager;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementExternalCollectionService;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementSharedRequestCollector;
import com.toadzip.backend.ingest.lh.collection.supply.domain.EmptyLhSupplyReplacementException;
import com.toadzip.backend.ingest.lh.collection.supply.domain.HeldLhSupplyReplacement;
import com.toadzip.backend.ingest.lh.collection.supply.domain.IncompleteLhSupplyReplacementException;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementUnavailableException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionMonitor;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(IngestExecutionExtension.class)
@ExtendWith(MockitoExtension.class)
class LhAnnouncementExternalCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");

    @Mock
    private LhAnnouncementExternalRepository externalRepository;

    @Mock
    private LhAnnouncementCatalogSourceReader catalogRepository;

    @Mock
    private LhStorageFixtures sourceStore;

    @Mock
    private LhAnnouncementCollectionProgressStore progressStore;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private LhAnnouncementExternalCollectionService service;

    private MockClock metricClock;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        metricClock = new MockClock();
        meterRegistry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, metricClock);
        service = service(2);
    }

    private LhAnnouncementExternalCollectionService service(int maxConcurrentRequests) {
        LhAnnouncementCollectionProgressManager progressManager =
                new LhAnnouncementCollectionProgressManager(
                        progressStore,
                        failureRecorder
                );
        var fixture = new CollectionServiceTestFixture();
        LhAnnouncementCandidateCollector candidateCollector = new LhAnnouncementCandidateCollector(
                fixture.query(externalRepository, sourceStore, new ExternalDataRetryExecutor(Duration.ZERO, meterRegistry),
                        meterRegistry), failureRecorder, progressManager);
        LhAnnouncementCollectionPolicy collectionPolicy = new LhAnnouncementCollectionPolicy(
                Clock.fixed(NOW, ZoneOffset.UTC),
                Period.ofDays(30)
        );
        return new LhAnnouncementExternalCollectionService(
                new LhAnnouncementCollectionCandidateResolver(catalogRepository, collectionPolicy),
                new LhAnnouncementSharedRequestCollector(candidateCollector, progressManager,
                        new LhAnnouncementClientProperties(
                                maxConcurrentRequests, Duration.ofSeconds(3), Duration.ofSeconds(10))),
                meterRegistry
        );
    }

    @Test
    void 공고목록의_임대_공고를_목록의_조회_코드로_수집한다() {
        source(catalogAnnouncement("300", "07", "062"));
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());

        ExternalDataCollectionReport report = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        ArgumentCaptor<LhAnnouncementRequest> request = ArgumentCaptor.forClass(LhAnnouncementRequest.class);
        verify(externalRepository).fetchDetail(request.capture());
        assertThat(request.getValue()).isEqualTo(new LhAnnouncementRequest("300", "03", "06", "07", "062"));
        assertThat(report.failedRequestCount()).isZero();
        verify(progressStore).save(any(), eq("lh-catalog:03:06:07:300"), any(), eq("300"));
    }

    @Test
    void 공고목록_요청_하나만_다시_조회하고_목록에_없는_요청은_거절한다() {
        source(catalogAnnouncement("300", "07", "062"), catalogAnnouncement("400", "07", "062"));
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        String target = new LhAnnouncementRequest("300", "03", "06", "07", "062").requestDescription();

        service.refreshCatalogTarget(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, target);

        ArgumentCaptor<LhAnnouncementRequest> request = ArgumentCaptor.forClass(LhAnnouncementRequest.class);
        verify(externalRepository).fetchDetail(request.capture());
        assertThat(request.getValue().panId()).isEqualTo("300");
        assertThatThrownBy(() -> service.refreshCatalogTarget(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL,
                new LhAnnouncementRequest("999", "03", "06", "07", "062").requestDescription()))
                .isInstanceOf(InvalidIngestRequestException.class);
    }

    @Test
    void 수집_후보와_요청_수와_목록_조회_시간을_기록한다() {
        when(catalogRepository.findAllPresentRentalHousingAnnouncements()).thenAnswer(invocation -> {
            metricClock.add(Duration.ofMillis(20));
            return List.of(announcementSource("a", "100"), announcementSource("b", "200"),
                    ended(announcementSource("old", "300"), "2026.08.01"));
        });
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(any(), any(), any())).thenReturn(1);
        var monitor = mock(DataPipelineExecutionMonitor.class);

        try (var scope = IngestExecutionScope.open(IngestExecutionScope.current().orElseThrow(), monitor)) {
            service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        }

        verify(monitor).beginWork(any(), eq("요청"), eq(2L));
        verify(monitor, times(2)).workCompleted();
        assertMetricCount("candidates", "scheduled", 2);
        assertMetricCount("requests", "scheduled", 2);
        assertThat(preparationTimer("scheduled").count()).isOne();
        assertThat(preparationTimer("scheduled").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(20);
    }

    @Test
    void 공고목록_조회가_실패하면_외부_호출_없이_원래_예외를_전파한다() {
        IllegalStateException failure = new IllegalStateException("목록 조회 실패");
        when(catalogRepository.findAllPresentRentalHousingAnnouncements()).thenThrow(failure);

        assertThatThrownBy(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)).isSameAs(failure);

        assertThat(preparationTimer("scheduled").count()).isOne();
        verify(externalRepository, never()).fetchDetail(any());
    }

    @Test
    void 수집할_공고가_없으면_외부_API를_호출하지_않는다() {
        source();

        ExternalDataCollectionReport report = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        assertThat(report.externalApiCallCount()).isZero();
        verify(externalRepository, never()).fetchDetail(any());
    }

    @Test
    void 연속_외부_장애에서는_남은_공고를_호출하지_않고_성공으로_완료하지_않는다() {
        List<LhAnnouncementCatalogSourceView> rows = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            rows.add(announcementSource("source-" + index, "pan-" + index));
        }
        source(rows.toArray(LhAnnouncementCatalogSourceView[]::new));
        var circuit = new LhAnnouncementCircuitBreaker(Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
        AtomicInteger networkCalls = new AtomicInteger();
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> circuit.execute(() -> {
            networkCalls.incrementAndGet();
            throw ExternalDataRequestException.retryable("LH 연결 장애");
        }));

        assertThatThrownBy(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .isInstanceOf(LhAnnouncementUnavailableException.class);

        assertThat(networkCalls.get()).isBetween(5, 6);
        verify(sourceStore, never()).replaceDetails(any(), any(), any());
        verify(progressStore, never()).save(any(), any(), any(), any());
    }

    @Test
    void 회로가_열리면_후속_공고를_중단하고_이미_시작한_성공_공고는_저장한다() throws Exception {
        source(announcementSource("a", "100"), announcementSource("b", "200"),
                announcementSource("c", "300"));
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
            if (request.panId().equals("100")) {
                blocked.countDown();
                throw new LhAnnouncementUnavailableException("LH 장애 차단");
            }
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return detailResponse();
        });
        when(sourceStore.replaceDetails(eq("200"), any(), any())).thenReturn(1);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(IngestExecutionScope.propagate(
                    () -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)));
            try {
                assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            }
            finally {
                release.countDown();
            }
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(LhAnnouncementUnavailableException.class);
        }
        verify(externalRepository, times(2)).fetchDetail(any());
        verify(progressStore).save(any(), eq(key("200")), any(), eq("200"));
        verify(progressStore, never()).save(any(), eq(key("100")), any(), any());
        assertMetricCount("candidates", "scheduled", 3);
        assertMetricCount("requests", "scheduled", 3);
        assertThat(preparationTimer("scheduled").count()).isOne();
    }

    @Test
    void 설정한_8개_요청을_동시에_처리하되_아홉번째는_완료_슬롯을_기다린다() throws Exception {
        service = service(8);
        List<LhAnnouncementCatalogSourceView> rows = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            rows.add(announcementSource("source-" + index, "pan-" + index));
        }
        source(rows.toArray(LhAnnouncementCatalogSourceView[]::new));
        CountDownLatch started = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return detailResponse();
            }
            finally {
                active.decrementAndGet();
            }
        });
        when(sourceStore.replaceDetails(any(), any(), any())).thenReturn(1);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(IngestExecutionScope.propagate(
                    () -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)));
            try {
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                verify(externalRepository, times(8)).fetchDetail(any());
            }
            finally {
                release.countDown();
            }
            assertThat(result.get(5, TimeUnit.SECONDS).externalApiCallCount()).isEqualTo(10);
            assertThat(peak.get()).isEqualTo(8);
        }
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
        when(sourceStore.replaceDetails(any(), any(), any())).thenReturn(1);

        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(IngestExecutionScope.propagate(
                    () -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)));
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
            verify(progressStore, times(4)).save(any(), any(), any(), any());
        }
    }

    @Test
    void 한_요청이_끝나면_다른_요청을_기다리지_않고_빈_슬롯에서_다음_공고를_수집한다() throws Exception {
        source(announcementSource("a", "100"), announcementSource("b", "200"),
                announcementSource("c", "300"));
        CountDownLatch slowRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowRequest = new CountDownLatch(1);
        CountDownLatch thirdRequestStarted = new CountDownLatch(1);
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
            if (request.panId().equals("200")) {
                slowRequestStarted.countDown();
                assertThat(releaseSlowRequest.await(5, TimeUnit.SECONDS)).isTrue();
            }
            if (request.panId().equals("100")) {
                assertThat(slowRequestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            }
            if (request.panId().equals("300")) {
                thirdRequestStarted.countDown();
            }
            return detailResponse();
        });
        when(sourceStore.replaceDetails(any(), any(), any())).thenReturn(1);

        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(IngestExecutionScope.propagate(
                    () -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)));
            try {
                assertThat(thirdRequestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            }
            finally {
                releaseSlowRequest.countDown();
            }
            assertThat(result.get(5, TimeUnit.SECONDS).externalApiCallCount()).isEqualTo(3);
        }
    }

    @Test
    void 호출_제한과_동시에_진행하던_성공_공고는_저장한다() throws Exception {
        source(announcementSource("a", "100"), announcementSource("b", "200"));
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
        when(sourceStore.replaceDetails(eq("200"), any(), any())).thenReturn(1);

        ExternalDataCollectionReport report = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        assertThat(report.storedRowCount()).isOne();
        assertThat(report.externalApiCallCount()).isEqualTo(2);
        assertThat(report.rateLimitedRequestCount()).isOne();
        verify(externalRepository, times(2)).fetchDetail(any());
        verify(sourceStore, never()).replaceDetails(eq("100"), any(), any());
        verify(progressStore).save(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL), eq(key("200")), any(), eq("200"));
        verify(progressStore, never()).save(any(), eq(key("100")), any(), any());
    }

    @Test
    void 호출_제한으로_열린_회로의_동시_거절도_부분_실패로_기록하고_후속_공고를_중단한다() throws Exception {
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
            throw new LhAnnouncementUnavailableException("LH 호출 제한 차단", true);
        });

        ExternalDataCollectionReport report = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        assertThat(report.failedRequestCount()).isEqualTo(2);
        assertThat(report.rateLimitedRequestCount()).isEqualTo(2);
        assertThat(report.externalApiCallCount()).isOne();
        verify(externalRepository, times(2)).fetchDetail(any());
        verify(progressStore, never()).save(any(), any(), any(), any());
    }

    @Test
    void 같은_panId의_다른_조회_조건은_저장과_체크포인트까지_순서대로_처리한다() {
        LhAnnouncementCatalogSourceView first = announcementSource("a", "100");
        LhAnnouncementCatalogSourceView second = catalogAnnouncement("100", "07", "063");
        source(first, announcementSource("c", "200"), second);
        Set<String> stored = ConcurrentHashMap.newKeySet();
        List<String> samePanConditions = new ArrayList<>();
        when(externalRepository.fetchDetail(any())).thenAnswer(invocation -> {
            LhAnnouncementRequest request = invocation.getArgument(0);
            if (request.panId().equals("100")) {
                if (request.announcementTypeCode().equals("07")) {
                    assertThat(stored).contains("100");
                    verify(progressStore).save(any(), eq(key("100")), any(), eq("100"));
                }
                samePanConditions.add(request.announcementTypeCode());
            }
            return detailResponse();
        });
        when(sourceStore.replaceDetails(any(), any(), any())).thenAnswer(invocation -> {
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
        when(sourceStore.replaceDetails(eq("100"), any(), any())).thenAnswer(invocation -> {
            failingStoreEntered.countDown();
            throw failure;
        });
        when(sourceStore.replaceDetails(eq("200"), any(), any())).thenReturn(1);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(IngestExecutionScope.propagate(
                    () -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)));
            try {
                assertThat(failingStoreEntered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            }
            finally {
                releaseOtherRequest.countDown();
            }
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCause(failure);
        }
        verify(externalRepository, times(2)).fetchDetail(any());
        verify(progressStore).save(any(), eq(key("200")), any(), eq("200"));
        verify(progressStore, never()).save(any(), eq(key("100")), any(), any());
    }

    @Test
    void 병렬_작업이_둘_다_실패하면_두번째_예외를_suppressed로_보존한다() {
        source(announcementSource("a", "100"), announcementSource("b", "200"));
        IllegalStateException firstFailure = new IllegalStateException("첫 번째 DB 저장 실패");
        IllegalArgumentException secondFailure = new IllegalArgumentException("두 번째 DB 저장 실패");
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any(), any())).thenThrow(firstFailure);
        when(sourceStore.replaceDetails(eq("200"), any(), any())).thenThrow(secondFailure);

        assertThatThrownBy(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .isSameAs(firstFailure)
                .satisfies(exception -> assertThat(exception.getSuppressed()).containsExactly(secondFailure));

        verify(externalRepository, times(2)).fetchDetail(any());
        verify(progressStore, never()).save(any(), any(), any(), any());
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
    void 병렬_요청_worker의_외부_요청을_실행_진행에_기록한다() {
        source(announcementSource("a", "100"), announcementSource("b", "200"));
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(any(), any(), any())).thenReturn(1);
        var monitor = mock(DataPipelineExecutionMonitor.class);

        try (var scope = IngestExecutionScope.open(IngestExecutionScope.current().orElseThrow(), monitor)) {
            service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        }

        verify(monitor, times(2)).requestStarted(any());
        verify(monitor, times(2)).requestFinished();
    }

    @Test
    void LH_상세_수집은_상세_행만_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any(), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(sourceStore).replaceDetails(eq("100"), eq(announcementRequestDescription()), any());
        verify(sourceStore, never()).replaceSupplies(any(), any(), any());
        verify(externalRepository).fetchDetail(any());
        verify(externalRepository, never()).fetchSupply(any());
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.successfulRequestCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void LH_상세가_100건을_초과해도_응답_전체를_한_번에_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse(0, 101));
        when(sourceStore.replaceDetails(eq("100"), any(), any())).thenReturn(101);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        ArgumentCaptor<List<LhAnnouncementDetailSourceView>> sources = ArgumentCaptor.captor();
        verify(externalRepository).fetchDetail(any());
        verify(sourceStore).replaceDetails(eq("100"), any(), sources.capture());
        assertThat(sources.getValue()).hasSize(101);
        assertThat(sources.getValue()).extracting(LhAnnouncementDetailSourceView::getSourceOrder)
                .containsExactlyElementsOf(IntStream.range(0, 101).boxed().toList());
        assertThat(result.storedRowCount()).isEqualTo(101);
        assertThat(result.externalApiCallCount()).isOne();
    }

    @Test
    void LH_상세는_여러_dataset의_합계가_100건을_넘어도_응답_전체를_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponseWithTwoDatasets(60));
        when(sourceStore.replaceDetails(eq("100"), any(), any())).thenReturn(120);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository).fetchDetail(any());
        verify(sourceStore).replaceDetails(eq("100"), any(), any());
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
        when(sourceStore.replaceSupplies(eq("100"), any(), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(sourceStore).replaceSupplies(eq("100"), eq(announcementRequestDescription()), any());
        verify(sourceStore, never()).replaceDetails(any(), any(), any());
        verify(externalRepository).fetchSupply(any());
        verify(externalRepository, never()).fetchDetail(any());
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void LH_공급이_100건을_초과해도_응답_전체를_한_번에_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse(0, 101));
        when(sourceStore.replaceSupplies(eq("100"), any(), any())).thenReturn(101);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        ArgumentCaptor<List<LhAnnouncementSupplySourceView>> sources = ArgumentCaptor.captor();
        verify(externalRepository).fetchSupply(any());
        verify(sourceStore).replaceSupplies(eq("100"), any(), sources.capture());
        assertThat(sources.getValue()).hasSize(101);
        assertThat(sources.getValue()).extracting(LhAnnouncementSupplySourceView::getSourceOrder)
                .containsExactlyElementsOf(IntStream.range(0, 101).boxed().toList());
        assertThat(result.storedRowCount()).isEqualTo(101);
        assertThat(result.externalApiCallCount()).isOne();
    }

    @Test
    void 공급이_정확히_100건이어도_추가_요청_없이_전체를_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse(0, 100));
        when(sourceStore.replaceSupplies(eq("100"), any(), any())).thenReturn(100);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        ArgumentCaptor<List<LhAnnouncementSupplySourceView>> sources = ArgumentCaptor.captor();
        verify(sourceStore).replaceSupplies(eq("100"), any(), sources.capture());
        assertThat(sources.getValue()).hasSize(100);
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.storedRowCount()).isEqualTo(100);
        assertThat(result.externalApiCallCount()).isOne();
        verify(progressStore).save(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 빈_응답이나_부분_교체_거절은_같은_요청을_공유하는_공고의_성공_연결도_갱신하지_않는다(boolean empty) {
        source(announcementSource("a", "100"));
        RuntimeException failure = new EmptyLhSupplyReplacementException();
        JsonNode fetched = response("[{\"dsList01\":[]}]");
        if (!empty) {
            failure = new IncompleteLhSupplyReplacementException(HeldLhSupplyReplacement.incompleteReason(1, "a".repeat(64)));
            fetched = supplyResponse();
        }
        when(externalRepository.fetchSupply(any())).thenReturn(fetched);
        when(sourceStore.replaceSupplies(eq("100"), any(), any()))
                .thenThrow(failure);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.successfulRequestCount()).isZero();
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.externalApiCallCount()).isOne();
        verify(progressStore, never()).save(any(), any(), any(), any());
        verify(failureRecorder).record(any(), any(), eq(failure), any(), any());
        verify(failureRecorder, never()).resolve(any(), any());
    }

    @Test
    void 빈_상세_교체를_거절하면_성공_연결을_갱신하지_않는다() {
        source(announcementSource("a", "100"));
        EmptyLhDetailReplacementException failure = new EmptyLhDetailReplacementException();
        when(externalRepository.fetchDetail(any())).thenReturn(response(
                "[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsEtcInfo\":[]}]"));
        when(sourceStore.replaceDetails(eq("100"), any(), any())).thenThrow(failure);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.storedRowCount()).isZero();
        verify(progressStore, never()).save(any(), any(), any(), any());
        verify(failureRecorder).record(any(), any(), eq(failure), any(), any());
    }

    @Test
    void LH_공급_저장_DB_실패는_빈_응답_실패로_처리하지_않는다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any(), any()))
                .thenThrow(new IllegalStateException("DB 저장 실패"));

        assertThatThrownBy(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 저장 실패");

        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        verify(progressStore, never()).save(any(), any(), any(), any());
    }

    @Test
    void LH_공급_단일_요청이_실패하면_기존_원천과_완료_체크포인트를_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any()))
                .thenThrow(ExternalDataRequestException.retryable("LH 공급 조회 실패"));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> {
                    assertThat(exception.getRequestDescription()).isEqualTo(announcementRequestDescription());
                    assertThat(exception.getAttemptCount()).isEqualTo(3);
                }
        );
        verify(externalRepository, times(3)).fetchSupply(any());
        verify(sourceStore, never()).replaceSupplies(any(), any(), any());
        verify(progressStore, never()).save(any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.externalApiCallCount()).isEqualTo(3);
    }

    @Test
    void 요청_실패_기록을_해결하지_못하면_완료_처리도_실패한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any(), any())).thenReturn(1);
        doThrow(new IllegalStateException("실패 기록 갱신 실패"))
                .when(failureRecorder)
                .resolve(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, announcementRequestDescription());

        assertThatThrownBy(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("실패 기록 갱신 실패");

        verify(sourceStore).replaceSupplies(eq("100"), any(), any());
        verify(progressStore).save(any(), any(), any(), any());
    }

    @Test
    void LH_상세_응답에_상세_dataset이_없으면_기존_snapshot과_체크포인트를_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]"));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(sourceStore, never()).replaceDetails(any(), any(), any());
        verify(progressStore, never()).save(any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 내용_없는_LH_상세는_원천과_성공_연결을_교체하지_않고_실패로_기록한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(response("""
                [{"resHeader":[{"SS_CODE":"Y"}]},{"dsSbd":[{}]}]
                """));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.storedRowCount()).isZero();
        verify(sourceStore, never()).replaceDetails(any(), any(), any());
        verify(progressStore, never()).save(any(), any(), any(), any());
        verify(failureRecorder).record(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                any(), any(ExternalDataCallFailureException.class), any(), any());
    }

    @Test
    void LH_공급_응답에_공급_dataset이_없으면_기존_snapshot과_체크포인트를_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]"));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(sourceStore, never()).replaceSupplies(any(), any(), any());
        verify(progressStore, never()).save(any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 공공임대_응답에_dsList02가_없으면_기존_공급_원천과_체크포인트를_보존한다() {
        source(publicRentalAnnouncementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(response("""
                [{"dsList01":[{"SBD_LGO_NM":"다른 유형 단지","HTY_NNA":"46형"}]}]
                """));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(sourceStore, never()).replaceSupplies(any(), any(), any());
        verify(progressStore, never()).save(any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 표준_임대_공급행의_주택형이_없으면_기존_공급_원천과_체크포인트를_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(response("""
                [{"dsList01":[{"SBD_LGO_NM":"가 단지"}]}]
                """));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(sourceStore, never()).replaceSupplies(any(), any(), any());
        verify(progressStore, never()).save(any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 공공임대_dsList02의_공급행을_원천에_저장한다() {
        source(publicRentalAnnouncementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(response("""
                [{"dsList01":[],"dsList02":[{"BZDT_NM":"가 단지","HTY_NM":"46형",
                 "RSDN_DDO_AR":"46.8","SPL_AR":"67.0","TOT_HSH_CNT":"100",
                 "SIL_HSH_CNT":"20","LS_GMY":"10000000","MM_RFE":"200000"}]}]
                """));
        when(sourceStore.replaceSupplies(eq("100"), any(), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        ArgumentCaptor<List<LhAnnouncementSupplySourceView>> supplies = ArgumentCaptor.captor();
        verify(sourceStore).replaceSupplies(eq("100"), any(), supplies.capture());
        assertThat(supplies.getValue()).singleElement().satisfies(supply -> {
            assertThat(supply.getComplexLabel()).isEqualTo("가 단지");
            assertThat(supply.getSuppliedUnitCount()).isEqualTo("20");
            assertThat(supply.getMonthlyRentText()).isEqualTo("200000");
        });
        assertThat(result.storedRowCount()).isOne();
    }

    @Test
    void LH_상세_dataset_타입이_잘못되면_기존_snapshot과_체크포인트를_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any()))
                .thenReturn(response("[{\"dsEtcInfo\":[]},{\"dsSbd\":\"invalid\"}]"));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(sourceStore, never()).replaceDetails(any(), any(), any());
        verify(progressStore, never()).save(any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void LH_공급_dataset_타입이_잘못되면_기존_snapshot과_체크포인트를_보존한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(response("[{\"dsList01\":1}]"));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(sourceStore, never()).replaceSupplies(any(), any(), any());
        verify(progressStore, never()).save(any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void LH_상세_저장_실패는_외부_API_실패로_기록하지_않는다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any(), any())).thenThrow(new IllegalStateException("DB 저장 실패"));

        assertThatThrownBy(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 저장 실패");

        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        verify(progressStore, never()).save(any(), any(), any(), any());
    }

    @Test
    void 상세_API_실패는_공급_API_수집을_막지_않는다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenThrow(new ExternalDataRequestException("상세 조회 실패"));
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any(), any())).thenReturn(1);

        ExternalDataCollectionReport detail = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        ExternalDataCollectionReport supply = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        assertThat(detail.failedRequestCount()).isOne();
        assertThat(supply.storedRowCount()).isOne();
    }

    @Test
    void 일반_수집은_동일한_응답도_실행마다_조회하고_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any(), any())).thenReturn(1);

        assertThat(service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY).externalApiCallCount()).isOne();
        assertThat(service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY).externalApiCallCount()).isOne();

        verify(externalRepository, times(2)).fetchSupply(any());
        verify(sourceStore, times(2)).replaceSupplies(eq("100"), any(), any());
        verify(progressStore, times(2)).save(any(), any(), any(), any());
    }

    @Test
    void 일반_수집에_성공하면_현재_요청의_완료와_연결을_갱신한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any(), any())).thenReturn(1);

        var result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(externalRepository).fetchSupply(any());
        verify(progressStore).save(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY,
                key("100"), announcementRequestDescription(), "100");
        assertThat(result.externalApiCallCount()).isOne();
    }

    @Test
    void 실패한_요청은_다음_실행에서_다시_호출한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any()))
                .thenThrow(new ExternalDataRequestException("일시적 실패"))
                .thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any(), any())).thenReturn(1);

        ExternalDataCollectionReport failed = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        ExternalDataCollectionReport retried = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository, org.mockito.Mockito.times(2)).fetchDetail(any());
        verify(progressStore).save(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                eq(key("100")),
                any(),
                eq("100")
        );
        assertThat(failed.failedRequestCount()).isOne();
        assertThat(retried.storedRowCount()).isOne();
    }

    @Test
    void 완료_체크포인트가_없으면_다시_호출한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any(), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository).fetchDetail(any());
        verify(sourceStore).replaceDetails(eq("100"), any(), any());
        verify(progressStore).save(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                eq(key("100")),
                any(),
                eq("100")
        );
        assertThat(result.externalApiCallCount()).isOne();
    }

    @Test
    void LH_상세나_공급이_아닌_API는_거절한다() {
        assertThatThrownBy(() -> service.collect(ExternalDataSource.MYHOME_ANNOUNCEMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LH 공고 API가 아닙니다.");
    }

    @Test
    void 종료_후_30일이_지난_공고는_정기_수집에서_제외하고_최근_종료와_마감일_없는_공고는_수집한다() {
        source(ended(announcementSource("old", "100"), "2026.08.19"),
                ended(announcementSource("recent", "200"), "2026.09.18"),
                announcementSource("open", "300"));
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(any(), any(), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        ArgumentCaptor<LhAnnouncementRequest> requests = ArgumentCaptor.forClass(LhAnnouncementRequest.class);
        verify(externalRepository, times(2)).fetchDetail(requests.capture());
        assertThat(requests.getAllValues()).extracting(LhAnnouncementRequest::panId)
                .containsExactlyInAnyOrder("200", "300");
        assertThat(result.successfulRequestCount()).isEqualTo(2);
    }

    @Test
    void 최근_종료_공고도_실행마다_실제_조회한다() {
        source(ended(announcementSource("recent", "100"), "2026.09.18"));
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any(), any())).thenReturn(1);

        service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository, times(2)).fetchDetail(any());
    }

    @Test
    void 강제_갱신은_종료_정책_밖의_LH_공고도_공고_ID로_다시_조회한다() {
        source(ended(announcementSource("old", "100"), "2026.08.01"), announcementSource("other", "200"));
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any(), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.refresh(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, " 100 ");

        ArgumentCaptor<LhAnnouncementRequest> request = ArgumentCaptor.forClass(LhAnnouncementRequest.class);
        verify(externalRepository).fetchDetail(request.capture());
        assertThat(request.getValue().panId()).isEqualTo("100");
        verify(progressStore).save(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL), eq(key("100")), any(), eq("100"));
        assertThat(result.externalApiCallCount()).isOne();
        assertMetricCount("candidates", "forced", 1);
        assertMetricCount("requests", "forced", 1);
        assertThat(preparationTimer("forced").count()).isOne();
        assertThat(meterRegistry.find("ingest.announcement.prepare").tag("mode", "scheduled").timers()).isEmpty();
    }

    @Test
    void 공고목록에_없는_LH_공고는_강제_갱신하지_않는다() {
        source(announcementSource("other", "200"));

        assertThatThrownBy(() -> service.refresh(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, "missing"))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessage("최신 LH 공고목록에서 공고를 찾을 수 없습니다: panId=missing");
        assertThatThrownBy(() -> service.refresh(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, " "))
                .isInstanceOf(InvalidIngestRequestException.class)
                .hasMessage("LH 공고 ID는 필수입니다.");

        verify(externalRepository, never()).fetchDetail(any());
    }

    private void assertMetricCount(String name, String mode, int count) {
        assertThat(meterRegistry.get("ingest.announcement." + name)
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "mode", mode, "result", "refresh")
                .counter().count()).isEqualTo(count);
    }

    private Timer preparationTimer(String mode) {
        return meterRegistry.get("ingest.announcement.prepare")
                .tags("source", "LH_ANNOUNCEMENT_DETAIL", "mode", mode, "phase", "catalog_read").timer();
    }

    private void source(LhAnnouncementCatalogSourceView... sources) {
        when(catalogRepository.findAllPresentRentalHousingAnnouncements()).thenReturn(List.of(sources));
    }

    /** 공고목록 수집 후보의 진행 키. 기본 행복주택 공고의 조회 코드(03·06·06)를 쓴다. */
    private static String key(String panId) {
        return LhAnnouncementCollectionCandidateResolver.CATALOG_SOURCE_PREFIX + "03:06:06:" + panId;
    }

    private LhAnnouncementCatalogSourceView announcementSource() {
        return announcementSource("default", "100");
    }

    /** 공고목록의 행복주택 공고. 이름은 테스트에서 공고를 구분해 읽기 위한 것이고 저장 값에는 쓰지 않는다. */
    private LhAnnouncementCatalogSourceView announcementSource(String name, String panId) {
        return catalogAnnouncement(panId, "06", "063");
    }

    private LhAnnouncementCatalogSourceView publicRentalAnnouncementSource() {
        return LhSourceViews.announcementCatalog(NOW, new LhAnnouncementCatalogSnapshot(
                "100", "03", "06", "06", "060", "공고", "공고중", "", "", "", "", ""));
    }

    private LhAnnouncementCatalogSourceView ended(LhAnnouncementCatalogSourceView source, String closingDate) {
        return LhSourceViews.announcementCatalog(NOW, new LhAnnouncementCatalogSnapshot(
                source.getPanId(), source.getConnectionSystemDivisionCode(), source.getUpperAnnouncementTypeCode(),
                source.getAnnouncementTypeCode(), source.getSupplyInfoTypeCode(), "공고", "접수마감", "", "",
                closingDate, "", ""));
    }

    private LhAnnouncementCatalogSourceView catalogAnnouncement(String panId, String announcementType, String supplyType) {
        return LhSourceViews.announcementCatalog(NOW, new LhAnnouncementCatalogSnapshot(
                panId, "03", "06", announcementType, supplyType, "공고", "공고중", "", "", "", "", ""));
    }

    private JsonNode detailResponse() {
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},"
                + "{\"dsEtcInfo\":[{\"CRC_RSN\":\"정정\"}]}]");
    }

    private JsonNode supplyResponse() {
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},"
                + "{\"dsList01\":[{\"SBD_LGO_NM\":\"행복주택\",\"HTY_NNA\":\"46A\"}]}]");
    }

    private JsonNode detailResponse(int start, int count) {
        String rows = IntStream.range(start, start + count)
                .mapToObj(index -> "{\"LCC_NT_NM\":\"단지-" + index + "\"}")
                .collect(Collectors.joining(","));
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsSbd\":[" + rows + "]}]");
    }

    private JsonNode detailResponseWithTwoDatasets(int countPerDataset) {
        String complexes = IntStream.range(0, countPerDataset)
                .mapToObj(index -> "{\"LCC_NT_NM\":\"단지-" + index + "\"}")
                .collect(Collectors.joining(","));
        String attachments = IntStream.range(0, countPerDataset)
                .mapToObj(index -> "{\"CMN_AHFL_NM\":\"첨부-" + index + "\"}")
                .collect(Collectors.joining(","));
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsSbd\":[" + complexes
                + "]},{\"dsAhflInfo\":[" + attachments + "]}]");
    }

    private JsonNode supplyResponse(int start, int count) {
        String rows = IntStream.range(start, start + count)
                .mapToObj(index -> "{\"SBD_LGO_NM\":\"단지-" + index + "\",\"HTY_NNA\":\"46A\"}")
                .collect(Collectors.joining(","));
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsList01\":[" + rows + "]}]");
    }

    private JsonNode response(String payload) {
        return JsonMapper.builder().build().readTree(payload);
    }

    private String announcementRequestDescription() {
        return "PAN_ID=100&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06"
                + "&SPL_INF_TP_CD=063&AIS_TP_CD=06&COLLECTION_VERSION=6";
    }
}
