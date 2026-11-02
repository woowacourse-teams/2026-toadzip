package com.toadzip.backend.ingest.collection.myhome.complex.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeRegion;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.api.MyHomeComplexCollectionCommand;
import com.toadzip.backend.ingest.collection.paging.service.PagedSourceCollector;
import com.toadzip.backend.ingest.collection.paging.service.RateLimitSignal;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRetryInterruptedException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MyHomeComplexRegionCollectionService {

    private final MyHomeComplexCollector collector;
    private final PagedSourceCollector pagedCollector;

    public MyHomeComplexCollectionReport collect(
            MyHomeRegion region,
            MyHomeComplexCollectionCommand request
    ) {
        return collect(region, request, new AtomicBoolean());
    }

    /** 다른 지역이 호출 제한에 걸리면 남은 호출을 멈추고 취소로 보고하며, 이 지역이 걸리면 다른 지역에 알린다. */
    public MyHomeComplexCollectionReport collect(
            MyHomeRegion region,
            MyHomeComplexCollectionCommand request,
            AtomicBoolean rateLimitReached
    ) {
        if (rateLimitReached.get()) {
            return MyHomeComplexCollectionReport.empty();
        }
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        ExternalDataCollectionReport report;
        try {
            report = pagedCollector.collectAndReport(collector, startedAt -> new MyHomeComplexCollectionRequest(
                    IngestExecutionScope.currentExecutionId().orElse(null), region.provinceCode(),
                    region.districtCode(), request.pageSize(), request.maxPages(), startedAt), callCounter,
                    new SharedRateLimit(rateLimitReached));
        }
        catch (RateLimitCollectionCancelledException exception) {
            return cancelledReport(callCounter);
        }
        catch (ExternalDataRetryInterruptedException exception) {
            if (rateLimitReached.get()) {
                return cancelledReport(callCounter);
            }
            throw exception;
        }
        return new MyHomeComplexCollectionReport(report.operation(), report.storedRowCount(),
                report.failedRequestCount(), report.externalApiCallCount(), report.rateLimitedRequestCount());
    }

    private MyHomeComplexCollectionReport cancelledReport(ExternalDataCallCounter callCounter) {
        return new MyHomeComplexCollectionReport(
                ExternalDataSource.MYHOME_COMPLEX.operation(),
                0,
                0,
                callCounter.count()
        );
    }

    private record SharedRateLimit(AtomicBoolean rateLimitReached) implements RateLimitSignal {

        @Override
        public void checkBeforeAttempt() {
            if (rateLimitReached.get()) {
                throw new RateLimitCollectionCancelledException();
            }
        }

        @Override
        public void reached() {
            rateLimitReached.set(true);
        }
    }

    private static final class RateLimitCollectionCancelledException extends RuntimeException {
    }
}
