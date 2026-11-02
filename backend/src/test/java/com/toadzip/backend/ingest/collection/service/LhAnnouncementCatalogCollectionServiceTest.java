package com.toadzip.backend.ingest.collection.service;

import static java.time.format.DateTimeFormatter.BASIC_ISO_DATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.dto.LhAnnouncementCatalogPage.Entry;
import com.toadzip.backend.ingest.collection.fixture.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.LhCatalogStorageFixtures.StoreResult;
import com.toadzip.backend.ingest.collection.fixture.repository.LhCatalogStorageFixtures;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogPage;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogRow;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.domain.LhAnnouncementCatalogSnapshot;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.repository.LhAnnouncementCatalogPageParser;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.service.LhAnnouncementCatalogCollectionService;
import com.toadzip.backend.ingest.collection.paging.domain.SourcePage;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(IngestExecutionExtension.class)
@ExtendWith(MockitoExtension.class)
class LhAnnouncementCatalogCollectionServiceTest {

    @Mock
    private LhAnnouncementExternalRepository externalRepository;
    @Mock
    private LhAnnouncementCatalogPageParser parser;
    @Mock
    private LhCatalogStorageFixtures store;
    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private LhAnnouncementCatalogCollectionService service;
    private CollectionServiceTestFixture fixture;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        when(externalRepository.fetchCatalog(anyInt(), eq(500))).thenReturn(
                JsonMapper.builder().build().createObjectNode()
        );
        meterRegistry = new SimpleMeterRegistry();
        fixture = new CollectionServiceTestFixture();
        service = fixture.catalog(externalRepository, parser, store,
                failureRecorder, new ExternalDataRetryExecutor(Duration.ZERO, meterRegistry), meterRegistry);
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
        verify(failureRecorder).resolveStartingWith(ExternalDataSource.LH_ANNOUNCEMENT_CATALOG, "PG_SZ=500");
    }

    @Test
    void 오늘부터_설정한_기간만큼_거슬러_공고목록을_요청한다() {
        when(parser.parse(any(), eq(1), eq(500))).thenReturn(page(0, 1, 1));
        when(store.store(any())).thenReturn(new StoreResult(1, 1, 0));

        service.collect();

        LocalDate today = LocalDate.now(CollectionServiceTestFixture.CLOCK);
        assertThat(fixture.catalogRequests).singleElement().satisfies(request -> {
            assertThat(request.startDate()).isEqualTo(today.minusYears(2));
            assertThat(request.endDate()).isEqualTo(today);
            assertThat(request.parameters()).containsEntry("PAN_ST_DT", BASIC_ISO_DATE.format(today.minusYears(2)))
                    .containsEntry("PAN_ED_DT", BASIC_ISO_DATE.format(today));
        });
    }

    @Test
    void 후속_페이지_실패시_이전_목록을_보존한다() {
        when(parser.parse(any(), eq(1), eq(500))).thenReturn(page(0, 500, 501));
        when(parser.parse(any(), eq(2), eq(500)))
                .thenThrow(new ExternalDataRequestException("후속 페이지 불일치"));

        var result = service.collect();

        assertThat(result.failedRequestCount()).isOne();
        verify(store, never()).store(any());
        verify(failureRecorder).record(eq(ExternalDataSource.LH_ANNOUNCEMENT_CATALOG), eq("PG_SZ=500"), any(), any(),
                any());
        verify(failureRecorder, never()).resolveStartingWith(any(), any());
    }

    @Test
    void 페이지간_전체_건수가_바뀌면_저장하지_않는다() {
        when(parser.parse(any(), eq(1), eq(500))).thenReturn(page(0, 500, 501));
        when(parser.parse(any(), eq(2), eq(500))).thenReturn(page(500, 1, 502));
        org.mockito.Mockito.lenient().when(parser.parse(any(), eq(3), eq(500))).thenReturn(page(501, 1, 502));
        org.mockito.Mockito.lenient().when(store.store(any())).thenReturn(new StoreResult(502, 502, 0));

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
                .thenReturn(new LhAnnouncementCatalogPage(new SourcePage<>(0, List.of()), startDate(), endDate()));
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
        List<LhAnnouncementCatalogRow> entries = IntStream.range(start, start + count)
                .mapToObj(index -> new LhAnnouncementCatalogRow(
                new LhAnnouncementCatalogSnapshot(String.valueOf(index), "03", "06", "48", "063", "공고",
                        "공고중", "2026.09.25", "20260925", "2026.10.25", "url", "url"), "{}"
        )).toList();
        return new LhAnnouncementCatalogPage(new SourcePage<>(total, entries), startDate(), endDate());
    }

    private String startDate() {
        return BASIC_ISO_DATE.format(LocalDate.now(CollectionServiceTestFixture.CLOCK).minusYears(2));
    }

    private String endDate() {
        return BASIC_ISO_DATE.format(LocalDate.now(CollectionServiceTestFixture.CLOCK));
    }
}
