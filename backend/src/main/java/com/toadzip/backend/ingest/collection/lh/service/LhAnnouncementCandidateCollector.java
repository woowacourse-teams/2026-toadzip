package com.toadzip.backend.ingest.collection.lh.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.history.domain.CollectionSource;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.lh.domain.LhAnnouncementQuery;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver.Candidate;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataRateLimit;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.exception.exception.EmptyLhDetailReplacementException;
import com.toadzip.backend.ingest.exception.exception.EmptyLhSupplyReplacementException;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.exception.exception.IncompleteLhSupplyReplacementException;
import com.toadzip.backend.ingest.exception.exception.LhAnnouncementUnavailableException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.failure.service.IngestExecutionContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 선정된 요청 하나를 조회·파싱하고 저장한 뒤 수집 완료를 기록한다.
 * 재시도는 외부 조회와 파싱에만 적용하며 저장과 완료 기록의 트랜잭션은 각 저장소·관리자가 소유한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LhAnnouncementCandidateCollector {

    private final LhAnnouncementQueryCollector collector;
    private final SourceCollectionRecordService records;
    private final ExternalDataRetryExecutor retryExecutor;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final ExternalDataFailureRecorder failureRecorder;
    private final LhAnnouncementCollectionProgressManager progressManager;

    public ExternalDataCollectionReport collect(ExternalDataSource targetSource, Candidate candidate) {
        LhAnnouncementRequest request = candidate.request();
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        int storedRowCount;
        try {
            storedRowCount = collectAndStore(targetSource, request, callCounter);
        }
        catch (ExternalDataCallFailureException | EmptyLhDetailReplacementException
                | EmptyLhSupplyReplacementException
                | IncompleteLhSupplyReplacementException exception) {
            return failedReport(targetSource, request, exception, callCounter);
        }
        catch (LhAnnouncementUnavailableException exception) {
            if (!exception.isRateLimited()) {
                throw exception;
            }
            return failedReport(targetSource, request, exception, callCounter);
        }
        progressManager.complete(targetSource, candidate);
        return new ExternalDataCollectionReport(
                targetSource.operation(),
                storedRowCount,
                0,
                callCounter.count(),
                0,
                0,
                1
        );
    }

    private ExternalDataCollectionReport failedReport(
            ExternalDataSource targetSource,
            LhAnnouncementRequest request,
            RuntimeException exception,
            ExternalDataCallCounter callCounter
    ) {
        failureRecorder.record(
                targetSource,
                request.requestDescription(),
                exception,
                log,
                "LH 외부 API 수집에 실패했습니다"
        );
        return new ExternalDataCollectionReport(
                targetSource.operation(),
                0,
                1,
                callCounter.count(),
                0,
                ExternalDataRateLimit.count(exception)
        );
    }

    private int collectAndStore(
            ExternalDataSource targetSource,
            LhAnnouncementRequest request,
            ExternalDataCallCounter callCounter
    ) {
        var query = new LhAnnouncementQuery(request.panId(), request.connectionSystemDivisionCode(),
                request.upperAnnouncementTypeCode(), request.announcementTypeCode(), request.supplyInfoTypeCode());
        var attempt = new LhAnnouncementCollectionRequest(IngestExecutionContext.currentExecutionId().orElse(null),
                CollectionSource.valueOf(targetSource.name()), query, 6, clock.instant());
        return records.storedRowCount(collector.collectWithinBatch(attempt, retryExecutor, callCounter, meterRegistry));
    }
}
