package com.toadzip.backend.ingest.collection.myhome.complex.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.history.service.SourceCollectionRecordService;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeRegion;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.api.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.service.ExternalDataRateLimit;
import com.toadzip.backend.ingest.collection.service.ExternalDataRetryExecutor;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.exception.exception.ExternalDataRetryInterruptedException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.failure.service.IngestExecutionContext;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MyHomeComplexRegionCollectionService {

    private final MyHomeComplexCollector collector;
    private final SourceCollectionRecordService records;
    private final ExternalDataFailureRecorder failureRecorder;
    private final ExternalDataRetryExecutor retryExecutor;
    private final Clock clock;

    public MyHomeComplexCollectionReport collect(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request
    ) {
        return collect(region, request, new AtomicBoolean());
    }

    public MyHomeComplexCollectionReport collect(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request,
            AtomicBoolean rateLimitReached
    ) {
        if (rateLimitReached.get()) {
            return MyHomeComplexCollectionReport.empty();
        }
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        int storedRowCount;
        try {
            var attempt =
                    new com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionRequest(
                    IngestExecutionContext.currentExecutionId().orElse(null), region.provinceCode(),
                    region.districtCode(), request.pageSize(), request.maxPages(), clock.instant());
            UUID id = collector.collectWithinBatch(attempt, retryExecutor, callCounter, rateLimitReached);
            storedRowCount = records.storedRowCount(id);
        }
        catch (MyHomeComplexCollector.RateLimitCollectionCancelledException exception) {
            return cancelledReport(callCounter);
        }
        catch (ExternalDataRetryInterruptedException exception) {
            if (rateLimitReached.get()) {
                return cancelledReport(callCounter);
            }
            throw exception;
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            return failedReport(region, request, rateLimitReached, callCounter, exception);
        }
        failureRecorder.resolveStartingWith(
                ExternalDataSource.MYHOME_COMPLEX,
                region.requestDescription() + "&pageNo="
        );
        return new MyHomeComplexCollectionReport(
                ExternalDataSource.MYHOME_COMPLEX.operation(),
                storedRowCount,
                0,
                callCounter.count()
        );
    }

    private MyHomeComplexCollectionReport cancelledReport(ExternalDataCallCounter callCounter) {
        return new MyHomeComplexCollectionReport(
                ExternalDataSource.MYHOME_COMPLEX.operation(),
                0,
                0,
                callCounter.count()
        );
    }

    private MyHomeComplexCollectionReport failedReport(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request,
            AtomicBoolean rateLimitReached,
            ExternalDataCallCounter callCounter,
            RuntimeException exception
    ) {
        int rateLimitedRequestCount = ExternalDataRateLimit.count(exception);
        if (rateLimitedRequestCount > 0) {
            rateLimitReached.set(true);
        }
        failureRecorder.record(
                ExternalDataSource.MYHOME_COMPLEX,
                request.requestDescription(region, 1),
                exception,
                log,
                "마이홈 단지 지역 수집에 실패했습니다"
        );
        return new MyHomeComplexCollectionReport(
                ExternalDataSource.MYHOME_COMPLEX.operation(),
                0,
                1,
                callCounter.count(),
                rateLimitedRequestCount
        );
    }

}
