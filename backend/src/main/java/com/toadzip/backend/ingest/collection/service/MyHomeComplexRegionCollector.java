package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexSourceItem;
import com.toadzip.backend.ingest.collection.dto.MyHomeRegion;
import com.toadzip.backend.ingest.collection.repository.MyHomeComplexExternalRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeComplexResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeComplexResponseParser.ValidatedPage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MyHomeComplexRegionCollector {

    private final MyHomeComplexResponseParser responseParser;
    private final MyHomeComplexExternalRepository externalRepository;
    private final MyHomeSourceStore sourceStore;
    private final ExternalDataFailureRecorder failureRecorder;
    private final ExternalDataRetryExecutor retryExecutor;

    public MyHomeComplexRegionCollector(
            MyHomeComplexResponseParser responseParser,
            MyHomeComplexExternalRepository externalRepository,
            MyHomeSourceStore sourceStore,
            ExternalDataFailureRecorder failureRecorder,
            ExternalDataRetryExecutor retryExecutor
    ) {
        this.responseParser = responseParser;
        this.externalRepository = externalRepository;
        this.sourceStore = sourceStore;
        this.failureRecorder = failureRecorder;
        this.retryExecutor = retryExecutor;
    }

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
        List<MyHomeComplexSourceItem> items;
        try {
            items = fetchCompleteRegion(region, request, callCounter, rateLimitReached);
        }
        catch (RateLimitCollectionCancelledException exception) {
            return MyHomeComplexCollectionReport.empty();
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            return failedReport(region, request, rateLimitReached, callCounter, exception);
        }
        int storedRowCount = sourceStore.replaceComplexRegion(region, items);
        return new MyHomeComplexCollectionReport("myhome-complex", storedRowCount, 0, callCounter.count());
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
                "myhome-complex",
                0,
                1,
                callCounter.count(),
                rateLimitedRequestCount
        );
    }

    private List<MyHomeComplexSourceItem> fetchCompleteRegion(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request,
            ExternalDataCallCounter callCounter,
            AtomicBoolean rateLimitReached
    ) {
        List<MyHomeComplexSourceItem> items = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            if (rateLimitReached.get()) {
                throw new RateLimitCollectionCancelledException();
            }
            int currentPage = page;
            String requestDescription = request.requestDescription(region, currentPage);
            ValidatedPage validatedPage = retryExecutor.execute(
                    ExternalDataSource.MYHOME_COMPLEX,
                    requestDescription,
                    () -> responseParser.validate(
                            externalRepository.fetch(region, request, currentPage),
                            items.size()
                    ),
                    callCounter
            );
            ExternalDataPage<MyHomeComplexSourceItem> parsedPage = responseParser.parseItems(validatedPage);
            items.addAll(parsedPage.items());
            failureRecorder.resolve(ExternalDataSource.MYHOME_COMPLEX, requestDescription);
            if (parsedPage.completesCollection(items.size(), request.pageSize())) {
                return items;
            }
        }
        throw new ExternalDataRequestException("마이홈 단지 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }

    private static final class RateLimitCollectionCancelledException extends RuntimeException {
    }
}
