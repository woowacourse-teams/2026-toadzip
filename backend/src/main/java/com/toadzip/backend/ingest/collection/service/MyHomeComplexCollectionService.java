package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeRegion;
import com.toadzip.backend.ingest.collection.repository.MyHomeRegionCatalog;
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

    private final MyHomeRegionCatalog regionCatalog;

    private final MyHomeComplexRegionCollector regionCollector;

    public MyHomeComplexCollectionService(
            MyHomeRegionCatalog regionCatalog,
            MyHomeComplexRegionCollector regionCollector
    ) {
        this.regionCatalog = regionCatalog;
        this.regionCollector = regionCollector;
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
            return regionCollector.collect(regions.getFirst(), request);
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
                        () -> regionCollector.collect(region, request, rateLimitReached)
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
}
