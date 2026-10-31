package com.toadzip.backend.ingest.collection.paging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.paging.domain.PageBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.PagedCollectionBuffer;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.paging.dto.PagedSourceRequest;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.CollectionServiceTestFixture;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineExecutionMonitor;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineStoppedException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PagedSourceCollectorTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    private final SourceCollectionRecordService records = mock(SourceCollectionRecordService.class);
    private final ExternalDataFailureRecorder failureRecorder = mock(ExternalDataFailureRecorder.class);
    private final PagedSourceCollector collector = new PagedSourceCollector(records,
            CollectionServiceTestFixture.retryWithoutDelay(), failureRecorder, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID recordId = UUID.randomUUID();
    private final TestSource source = new TestSource();

    @BeforeEach
    void startRecords() {
        when(records.start(any())).thenReturn(recordId);
        when(records.storedRowCount(recordId)).thenReturn(3);
    }

    @Test
    void 모든_페이지를_모아_한_번에_저장하고_이전_실패_기록을_해소한다() {
        source.page(1, () -> new SourcePage<>(3, List.of("a", "b")));
        source.page(2, () -> new SourcePage<>(3, List.of("c")));

        var report = collector.collectAndReport(source, TestRequest::new);

        assertThat(report.storedRowCount()).isEqualTo(3);
        assertThat(report.failedRequestCount()).isZero();
        assertThat(report.externalApiCallCount()).isEqualTo(2);
        assertThat(source.stored).containsExactly("a", "b", "c");
        verify(failureRecorder).resolveStartingWith(ExternalDataSource.LH_LEASE_CATALOG, "PAGE=");
    }

    @Test
    void 페이지_검증_실패는_이력에_원래_실패를_남기고_마지막_페이지의_호출_실패로_바꾼다() {
        source.page(1, () -> new SourcePage<>(3, List.of("a", "b")));
        source.page(2, () -> new SourcePage<>(4, List.of("c")));

        assertThatThrownBy(() -> collector.collect(source, TestRequest::new, new ExternalDataCallCounter()))
                .isInstanceOfSatisfying(ExternalDataCallFailureException.class, failure -> {
                    assertThat(failure.getRequestDescription()).isEqualTo("PAGE=2");
                    assertThat(failure.getAttemptCount()).isOne();
                });

        verify(records).fail(eq(recordId), any(), isA(IllegalArgumentException.class));
        assertThat(source.stored).isNull();
    }

    @Test
    void 저장_중_검증_실패도_호출_실패로_바꾸고_이력에는_원래_실패를_남긴다() {
        source.page(1, () -> new SourcePage<>(1, List.of("a")));
        var failure = new IllegalArgumentException("저장할 원천이 요청과 다릅니다.");
        source.storeFailure = failure;

        assertThatThrownBy(() -> collector.collect(source, TestRequest::new, new ExternalDataCallCounter()))
                .isInstanceOf(ExternalDataCallFailureException.class);

        verify(records).fail(eq(recordId), any(), same(failure));
    }

    @Test
    void 요청을_만들다_실패하면_호출_실패로_바꾸지_않고_이력도_열지_않는다() {
        assertThatThrownBy(() -> collector.collect(source, startedAt -> new TestRequest(0, startedAt),
                new ExternalDataCallCounter())).isExactlyInstanceOf(IllegalArgumentException.class);

        verify(records, never()).start(any());
    }

    @Test
    void 외부_호출_실패는_대체_설명과_원천_문구로_기록하고_실패_보고로_돌려준다() {
        source.page(1, () -> {
            throw new ExternalDataRequestException("응답 형식이 올바르지 않습니다.");
        });

        var report = collector.collectAndReport(source, TestRequest::new);

        assertThat(report.failedRequestCount()).isOne();
        assertThat(report.storedRowCount()).isZero();
        assertThat(report.externalApiCallCount()).isOne();
        verify(failureRecorder).record(eq(ExternalDataSource.LH_LEASE_CATALOG), eq("PAGE=1"),
                isA(ExternalDataCallFailureException.class), any(), eq("시험 원천 수집 실패"));
        verify(failureRecorder, never()).resolveStartingWith(any(), any());
    }

    @Test
    void 호출_제한에_걸리면_실패를_기록하기_전에_함께_수집하는_단위에_알린다() {
        var rateLimit = mock(RateLimitSignal.class);
        source.page(1, () -> {
            throw ExternalDataRequestException.rateLimited("호출 제한");
        });

        var report = collector.collectAndReport(source, TestRequest::new, new ExternalDataCallCounter(), rateLimit);

        assertThat(report.rateLimitedRequestCount()).isOne();
        InOrder order = inOrder(rateLimit, failureRecorder);
        order.verify(rateLimit).checkBeforeAttempt();
        order.verify(rateLimit).reached();
        order.verify(failureRecorder).record(any(), any(), any(), any(), any());
    }

    @Test
    void 페이지를_부르기_전에_중지_요청을_호출_제한_확인보다_먼저_반영한다() {
        var monitor = mock(DataPipelineExecutionMonitor.class);
        var rateLimit = mock(RateLimitSignal.class);
        doNothing().doThrow(new DataPipelineStoppedException()).when(monitor).checkStopRequested();
        source.page(1, () -> new SourcePage<>(1, List.of("a")));

        try (var ignored = IngestExecutionScope.open(null, monitor)) {
            assertThatThrownBy(() -> collector.collectAndReport(
                    source, TestRequest::new, new ExternalDataCallCounter(), rateLimit))
                    .isInstanceOf(DataPipelineStoppedException.class);
        }

        verify(rateLimit, never()).checkBeforeAttempt();
        verify(records).fail(eq(recordId), any(), isA(DataPipelineStoppedException.class));
    }

    @Test
    void 페이지_진행을_끈_원천은_페이지마다_진행을_알리지_않는다() {
        var monitor = mock(DataPipelineExecutionMonitor.class);
        source.page(1, () -> new SourcePage<>(1, List.of("a")));

        try (var ignored = IngestExecutionScope.open(null, monitor)) {
            collector.collect(source, TestRequest::new, new ExternalDataCallCounter());
            source.reportsPageProgress = false;
            collector.collect(source, TestRequest::new, new ExternalDataCallCounter());
        }

        verify(monitor).pageCompleted(1, 1, 2);
        verify(monitor).pageCompleted(anyInt(), anyInt(), anyInt());
    }

    private record TestRequest(int pageSize, Instant startedAt) implements PagedSourceRequest {

        private TestRequest(Instant startedAt) {
            this(2, startedAt);
        }

        private TestRequest {
            if (pageSize < 1) {
                throw new IllegalArgumentException("페이지 크기가 올바르지 않습니다.");
            }
        }

        @Override
        public int maxPages() {
            return 10;
        }

        @Override
        public UUID executionId() {
            return null;
        }

        @Override
        public CollectionSource source() {
            return CollectionSource.LH_LEASE_CATALOG;
        }

        @Override
        public Map<String, String> parameters() {
            return Map.of("pageSize", Integer.toString(pageSize));
        }

        @Override
        public String pageDescription(int page) {
            return resolvedFailurePrefix() + page;
        }

        @Override
        public String resolvedFailurePrefix() {
            return "PAGE=";
        }
    }

    private static final class TestSource implements PagedSource<TestRequest, SourcePage<String>, List<String>> {

        private final Map<Integer, Supplier<SourcePage<String>>> pages = new HashMap<>();
        private RuntimeException storeFailure;
        private List<String> stored;
        private boolean reportsPageProgress = true;

        private void page(int page, Supplier<SourcePage<String>> response) {
            pages.put(page, response);
        }

        @Override
        public ExternalDataSource source() {
            return ExternalDataSource.LH_LEASE_CATALOG;
        }

        @Override
        public SourcePage<String> fetch(TestRequest request, int page) {
            return pages.get(page).get();
        }

        @Override
        public PageBuffer<SourcePage<String>, List<String>> newBuffer(TestRequest request) {
            var rows = new PagedCollectionBuffer<String>("시험 원천");
            return new PageBuffer<>() {
                @Override
                public void add(SourcePage<String> page) {
                    rows.add(page, row -> {
                    });
                }

                @Override
                public int totalCount() {
                    return rows.totalCount();
                }

                @Override
                public boolean isComplete() {
                    return rows.isComplete();
                }

                @Override
                public List<String> finish(Instant collectedAt) {
                    return rows.finish().rows();
                }
            };
        }

        @Override
        public void store(UUID recordId, TestRequest request, List<String> collected) {
            if (storeFailure != null) {
                throw storeFailure;
            }
            stored = collected;
        }

        @Override
        public String failureLogMessage() {
            return "시험 원천 수집 실패";
        }

        @Override
        public boolean reportsPageProgress() {
            return reportsPageProgress;
        }
    }
}
