package com.toadzip.backend.ingest.collection.lh.leasecatalog.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.api.LhLeaseCatalogCollectionRequest;
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
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LhLeaseCatalogCollectionService {

    private final IngestOperationLock executionLock;
    private final LhLeaseCatalogCollector collector;
    private final SourceCollectionRecordService records;
    private final ExternalDataFailureRecorder failureRecorder;
    private final ExternalDataRetryExecutor retryExecutor;
    private final Clock clock;

    public ExternalDataCollectionReport collect(LhLeaseCatalogCollectionRequest request) {
        return executionLock.tryRun(IngestOperationLock.Operation.LH_LEASE_CATALOG_COLLECTION,
                () -> collectUnlocked(request)).orElseThrow(
                () -> new IngestAlreadyRunningException("LH 임대 카탈로그 수집이 이미 실행 중입니다."));
    }

    private ExternalDataCollectionReport collectUnlocked(LhLeaseCatalogCollectionRequest request) {
        var counter = new ExternalDataCallCounter();
        int stored;
        IngestExecutionScope.beginWork("LH 임대 카탈로그", "페이지", -1);
        try {
            var attempt =
                    new com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.LhLeaseCatalogCollectionRequest(
                    IngestExecutionContext.currentExecutionId().orElse(null), request.pageSize(),
                    request.maxPages(), clock.instant());
            stored = records.storedRowCount(collector.collectWithinBatch(attempt, retryExecutor, counter));
        } catch (ExternalDataCallFailureException | ExternalDataRequestException failure) {
            failureRecorder.record(ExternalDataSource.LH_LEASE_CATALOG, request.requestDescription(1),
                    failure, log, "LH 임대 카탈로그 수집 실패");
            return new ExternalDataCollectionReport(ExternalDataSource.LH_LEASE_CATALOG.operation(),
                    0, 1, counter.count(), 0, ExternalDataRateLimit.count(failure));
        }
        failureRecorder.resolveStartingWith(ExternalDataSource.LH_LEASE_CATALOG, "PG_SZ=");
        return new ExternalDataCollectionReport(ExternalDataSource.LH_LEASE_CATALOG.operation(),
                stored, 0, counter.count());
    }
}
