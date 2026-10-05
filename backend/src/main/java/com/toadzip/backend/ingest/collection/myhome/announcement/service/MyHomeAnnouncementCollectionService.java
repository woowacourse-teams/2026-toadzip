package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MyHomeAnnouncementCollectionService {

    private final IngestOperationLock executionLock;
    private final MyHomeAnnouncementCollector collector;
    private final MyHomeAnnouncementLifecycleService lifecycle;
    private final SourceCollectionRecordService records;
    private final ExternalDataFailureRecorder failureRecorder;
    private final ExternalDataRetryExecutor retryExecutor;
    private final Clock clock;

    public ExternalDataCollectionReport collect(
            com.toadzip.backend.ingest.collection.myhome.announcement.dto.api.MyHomeAnnouncementCollectionRequest request
    ) {
        return executionLock.tryRun(IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_COLLECTION,
                () -> collectUnlocked(request)).orElseThrow(
                () -> new IngestAlreadyRunningException("마이홈 공고 수집이 이미 실행 중입니다."));
    }

    private ExternalDataCollectionReport collectUnlocked(
            com.toadzip.backend.ingest.collection.myhome.announcement.dto.api.MyHomeAnnouncementCollectionRequest request
    ) {
        UUID runId = IngestExecutionContext.currentExecutionId().orElseGet(UUID::randomUUID);
        var report = ExternalDataCollectionReport.empty(ExternalDataSource.MYHOME_ANNOUNCEMENT.operation());
        for (MyHomeAnnouncementSupplyType type : MyHomeAnnouncementSupplyType.values()) {
            IngestExecutionScope.beginWork("마이홈 공고 · " + type.requestCode(), "페이지", -1);
            var result = collectSupplyType(runId, type, request);
            report = report.plus(result);
            if (result.rateLimitedRequestCount() > 0) {
                return report;
            }
        }
        if (report.failedRequestCount() == 0) {
            lifecycle.completeRun(runId);
        }
        return report;
    }

    private ExternalDataCollectionReport collectSupplyType(
            UUID runId, MyHomeAnnouncementSupplyType type,
            com.toadzip.backend.ingest.collection.myhome.announcement.dto.api.MyHomeAnnouncementCollectionRequest request
    ) {
        var counter = new ExternalDataCallCounter();
        int stored;
        try {
            var attempt = new MyHomeAnnouncementCollectionRequest(
                    runId, type.requestCode(), request.pageSize(), request.maxPages(), clock.instant());
            stored = records.storedRowCount(collector.collectWithinBatch(attempt, retryExecutor, counter));
        } catch (ExternalDataCallFailureException | ExternalDataRequestException failure) {
            failureRecorder.record(ExternalDataSource.MYHOME_ANNOUNCEMENT, request.requestDescription(type, 1),
                    failure, log, "마이홈 공고 공급유형 수집 실패");
            return new ExternalDataCollectionReport(ExternalDataSource.MYHOME_ANNOUNCEMENT.operation(),
                    0, 1, counter.count(), 0, ExternalDataRateLimit.count(failure));
        }
        failureRecorder.resolveStartingWith(ExternalDataSource.MYHOME_ANNOUNCEMENT,
                "suplyTy=" + type.requestCode() + "&pageNo=");
        return new ExternalDataCollectionReport(ExternalDataSource.MYHOME_ANNOUNCEMENT.operation(),
                stored, 0, counter.count());
    }
}
