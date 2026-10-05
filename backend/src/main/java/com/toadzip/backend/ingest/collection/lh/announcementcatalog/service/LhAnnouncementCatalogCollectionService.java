package com.toadzip.backend.ingest.collection.lh.announcementcatalog.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.lh.announcementcatalog.dto.LhAnnouncementCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataRateLimit;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.failure.service.IngestExecutionContext;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LhAnnouncementCatalogCollectionService {

    private static final ExternalDataSource SOURCE = ExternalDataSource.LH_ANNOUNCEMENT_CATALOG;
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 1_000;

    private final LhAnnouncementCatalogCollector collector;
    private final IngestOperationLock executionLock;
    private final ExternalDataRetryExecutor retryExecutor;
    private final ExternalDataFailureRecorder failureRecorder;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ExternalDataCollectionReport collect() {
        return executionLock.tryRun(IngestOperationLock.Operation.LH_ANNOUNCEMENT_COLLECTION, this::collectUnlocked)
                .orElseThrow(() -> new IngestAlreadyRunningException("LH 공고 목록 수집이 이미 실행 중입니다."));
    }

    private ExternalDataCollectionReport collectUnlocked() {
        var counter = new ExternalDataCallCounter();
        LhAnnouncementCatalogStorageService.StoreResult result;
        IngestExecutionScope.beginWork("LH 공고 목록", "페이지", -1);
        try {
            var request = new LhAnnouncementCatalogCollectionRequest(
                    IngestExecutionContext.currentExecutionId().orElse(null), PAGE_SIZE, MAX_PAGES, clock.instant());
            result = collector.collectWithinBatch(request, retryExecutor, counter);
        } catch (ExternalDataCallFailureException | ExternalDataRequestException failure) {
            failureRecorder.record(SOURCE, "PG_SZ=" + PAGE_SIZE, failure, log, "LH 공고 목록 수집 실패");
            return new ExternalDataCollectionReport(SOURCE.operation(), 0, 1, counter.count(),
                    0, ExternalDataRateLimit.count(failure));
        }
        failureRecorder.resolveStartingWith(SOURCE, "PG_SZ=" + PAGE_SIZE);
        meterRegistry.counter("ingest.announcement.catalog.rows", "change", "new").increment(result.newRowCount());
        meterRegistry.counter("ingest.announcement.catalog.rows", "change", "changed")
                .increment(result.changedRowCount());
        meterRegistry.counter("ingest.announcement.catalog.rows", "change", "unchanged")
                .increment(result.unchangedRowCount());
        return new ExternalDataCollectionReport(SOURCE.operation(), result.storedRowCount(), 0, counter.count());
    }
}
