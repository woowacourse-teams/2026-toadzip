package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexSourceItem;
import com.toadzip.backend.ingest.collection.dto.MyHomeRegion;
import com.toadzip.backend.ingest.collection.repository.MyHomeComplexExternalRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeRegionCatalog;
import com.toadzip.backend.ingest.collection.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeComplexResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeComplexResponseParser.ValidatedPage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MyHomeComplexCollectionService {

    private static final int MAX_CONCURRENT_REGIONS = 4;

    private final MyHomeComplexResponseParser responseParser;

    private final MyHomeComplexExternalRepository externalRepository;

    private final MyHomeRegionCatalog regionCatalog;

    private final MyHomeSourceStore sourceStore;

    private final ExternalDataFailureRecorder failureRecorder;

    private final ExternalDataRetryExecutor retryExecutor;

    public MyHomeComplexCollectionService(
            MyHomeComplexResponseParser responseParser,
            MyHomeComplexExternalRepository externalRepository,
            MyHomeRegionCatalog regionCatalog,
            MyHomeSourceStore sourceStore,
            ExternalDataFailureRecorder failureRecorder,
            ExternalDataRetryExecutor retryExecutor
    ) {
        this.responseParser = responseParser;
        this.externalRepository = externalRepository;
        this.regionCatalog = regionCatalog;
        this.sourceStore = sourceStore;
        this.failureRecorder = failureRecorder;
        this.retryExecutor = retryExecutor;
    }

    public MyHomeComplexCollectionReport collect(MyHomeComplexCollectionRequest request) {
        log.info(
                "마이홈 단지 수집을 시작합니다: pageSize={}, maxPages={}, allRegions={}, maxConcurrentRegions={}",
                request.pageSize(),
                request.maxPages(),
                request.requestsAllRegions(),
                MAX_CONCURRENT_REGIONS
        );
        List<MyHomeRegion> regions = regionsFor(request);
        MyHomeComplexCollectionReport report = collectRegions(regions, request);
        log.info(
                "마이홈 단지 수집을 완료했습니다: storedRowCount={}, failedRequestCount={}, externalApiCallCount={}",
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount()
        );
        return report;
    }

    private MyHomeComplexCollectionReport collectRegions(
            List<MyHomeRegion> regions,
            MyHomeComplexCollectionRequest request
    ) {
        if (regions.size() == 1) {
            return collectRegion(regions.getFirst(), request);
        }
        return collectRegionsConcurrently(regions, request);
    }

    private MyHomeComplexCollectionReport collectRegionsConcurrently(
            List<MyHomeRegion> regions,
            MyHomeComplexCollectionRequest request
    ) {
        MyHomeComplexCollectionReport report = MyHomeComplexCollectionReport.empty();
        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_REGIONS);
        AtomicBoolean rateLimitReached = new AtomicBoolean();
        CompletionService<MyHomeComplexCollectionReport> completionService =
                new ExecutorCompletionService<>(executor);
        List<Future<MyHomeComplexCollectionReport>> futures = regions.stream()
                .map(region -> completionService.submit(
                        () -> collectRegion(region, request, rateLimitReached)
                ))
                .toList();
        try {
            for (int completedCount = 0; completedCount < regions.size(); completedCount++) {
                MyHomeComplexCollectionReport regionReport = await(completionService);
                report = report.plus(regionReport);
                if (regionReport.rateLimitedRequestCount() > 0) {
                    cancel(futures);
                    return report;
                }
            }
            return report;
        }
        finally {
            shutdown(executor, rateLimitReached.get());
        }
    }

    private MyHomeComplexCollectionReport await(
            CompletionService<MyHomeComplexCollectionReport> completionService
    ) {
        try {
            return completionService.take().get();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("마이홈 단지 수집이 중단되었습니다.", exception);
        }
        catch (ExecutionException exception) {
            throw runtimeExceptionOf(exception.getCause());
        }
    }

    private void cancel(List<Future<MyHomeComplexCollectionReport>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    private void shutdown(ExecutorService executor, boolean immediately) {
        if (immediately) {
            executor.shutdownNow();
            return;
        }
        executor.shutdown();
    }

    private MyHomeComplexCollectionReport collectRegion(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request
    ) {
        return collectRegion(region, request, new AtomicBoolean());
    }

    private MyHomeComplexCollectionReport collectRegion(
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
        int storedRowCount = sourceStore.replaceComplexRegion(region, items);
        return new MyHomeComplexCollectionReport("myhome-complex", storedRowCount, 0, callCounter.count());
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

    private List<MyHomeRegion> regionsFor(MyHomeComplexCollectionRequest request) {
        if (request.requestsAllRegions()) {
            return regionCatalog.findAll();
        }
        return List.of(regionCatalog.find(request.provinceCode(), request.districtCode()));
    }

    private RuntimeException runtimeExceptionOf(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("마이홈 단지 수집 작업이 실패했습니다.", cause);
    }

    private static final class RateLimitCollectionCancelledException
            extends RuntimeException {
    }
}
