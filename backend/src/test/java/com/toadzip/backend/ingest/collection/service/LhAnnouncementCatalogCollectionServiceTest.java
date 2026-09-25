package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementCatalogPage;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCatalogStore;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCatalogStore.StoreResult;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementCollectionExecutionLock;
import com.toadzip.backend.ingest.collection.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementCatalogResponseParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LhAnnouncementCatalogCollectionServiceTest {

    @Mock
    private LhAnnouncementExternalRepository externalRepository;
    @Mock
    private LhAnnouncementCatalogResponseParser parser;
    @Mock
    private LhAnnouncementCatalogStore store;
    @Mock
    private LhAnnouncementCollectionExecutionLock lock;
    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private LhAnnouncementCatalogCollectionService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        when(lock.<ExternalDataCollectionReport>tryRun(any(), any())).thenAnswer(invocation -> {
            Supplier<ExternalDataCollectionReport> operation = invocation.getArgument(1);
            return Optional.of(operation.get());
        });
        when(externalRepository.fetchCatalog(anyInt(), eq(500))).thenReturn(
                new ExternalDataResponse("{}", JsonMapper.builder().build().createObjectNode())
        );
        meterRegistry = new SimpleMeterRegistry();
        service = new LhAnnouncementCatalogCollectionService(
                externalRepository, parser, store, lock,
                new ExternalDataRetryExecutor(Duration.ZERO, meterRegistry), failureRecorder, meterRegistry
        );
    }

    @Test
    void 모든_페이지를_검증한_후_목록을_한번에_저장한다() {
        when(parser.parse(any(), eq(1), eq(500))).thenReturn(page(0, 500, 501));
        when(parser.parse(any(), eq(2), eq(500))).thenReturn(page(500, 1, 501));
        when(store.store(any())).thenReturn(new StoreResult(501, 3, 2));

        var result = service.collect();

        assertThat(result.externalApiCallCount()).isEqualTo(2);
        assertThat(result.storedRowCount()).isEqualTo(501);
        assertThat(result.failedRequestCount()).isZero();
        ArgumentCaptor<List<Entry>> captured = ArgumentCaptor.forClass(List.class);
        verify(store).store(captured.capture());
        assertThat(captured.getValue()).hasSize(501);
        assertThat(meterRegistry.counter("ingest.announcement.catalog.rows", "change", "new").count())
                .isEqualTo(3);
        assertThat(meterRegistry.counter("ingest.announcement.catalog.rows", "change", "changed").count())
                .isEqualTo(2);
        assertThat(meterRegistry.counter("ingest.announcement.catalog.rows", "change", "unchanged").count())
                .isEqualTo(496);
    }

    @Test
    void 후속_페이지_실패시_이전_목록을_보존한다() {
        when(parser.parse(any(), eq(1), eq(500))).thenReturn(page(0, 500, 501));
        when(parser.parse(any(), eq(2), eq(500)))
                .thenThrow(new ExternalDataRequestException("후속 페이지 불일치"));

        var result = service.collect();

        assertThat(result.failedRequestCount()).isOne();
        verify(store, never()).store(any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
    }

    @Test
    void 페이지간_전체_건수가_바뀌면_저장하지_않는다() {
        when(parser.parse(any(), eq(1), eq(500))).thenReturn(page(0, 500, 501));
        when(parser.parse(any(), eq(2), eq(500))).thenReturn(page(500, 1, 502));

        assertThat(service.collect().failedRequestCount()).isOne();
        verify(store, never()).store(any());
    }

    @Test
    void 다른_페이지에_같은_공고가_있으면_저장하지_않는다() {
        when(parser.parse(any(), eq(1), eq(500))).thenReturn(page(0, 500, 501));
        when(parser.parse(any(), eq(2), eq(500))).thenReturn(page(0, 1, 501));

        assertThat(service.collect().failedRequestCount()).isOne();
        verify(store, never()).store(any());
    }

    @Test
    void 전체건수_증거가_없는_빈_목록은_실패로_보고하고_저장하지_않는다() {
        when(parser.parse(any(), eq(1), eq(500)))
                .thenReturn(new LhAnnouncementCatalogPage(List.of(), 0, "20260725", "20260925"));
        org.mockito.Mockito.lenient().when(store.store(any())).thenReturn(new StoreResult(0, 0, 0));

        assertThat(service.collect().failedRequestCount()).isOne();
        verify(store, never()).store(any());
    }

    @Test
    void 전체건수가_페이지크기와_같으면_불필요한_다음_페이지를_호출하지_않는다() {
        when(parser.parse(any(), eq(1), eq(500))).thenReturn(page(0, 500, 500));
        when(store.store(any())).thenReturn(new StoreResult(500, 500, 0));

        assertThat(service.collect().externalApiCallCount()).isOne();
        verify(externalRepository, never()).fetchCatalog(2, 500);
    }

    private LhAnnouncementCatalogPage page(int start, int count, int total) {
        List<Entry> entries = IntStream.range(start, start + count).mapToObj(index -> new Entry(
                new LhAnnouncementCatalogSnapshot(String.valueOf(index), "03", "06", "48", "063", "공고",
                        "공고중", "2026.09.25", "20260925", "2026.10.25", "url", "url"), "{}"
        )).toList();
        return new LhAnnouncementCatalogPage(entries, total, "20260725", "20260925");
    }
}
