package com.toadzip.backend.ingest.collection.myhome.complex.service;

import com.toadzip.backend.ingest.collection.myhome.complex.domain.MyHomeRegion;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.myhome.complex.dto.api.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.complex.repository.MyHomeRegionCatalog;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import com.toadzip.backend.ingest.pipeline.service.DataPipelineStoppedException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MyHomeComplexCollectionService {

    private static final int MAX_CONCURRENT_REGIONS = 4;

    private final MyHomeRegionCatalog regionCatalog;

    private final MyHomeComplexRegionCollectionService regionCollector;

    private final IngestOperationLock executionLock;

    public MyHomeComplexCollectionReport collect(MyHomeComplexCollectionRequest request) {
        return executionLock.tryRun(IngestOperationLock.Operation.MYHOME_COMPLEX_COLLECTION,
                () -> collectUnlocked(request)).orElseThrow(
                () -> new IngestAlreadyRunningException("마이홈 단지 수집이 이미 실행 중입니다."));
    }

    private MyHomeComplexCollectionReport collectUnlocked(MyHomeComplexCollectionRequest request) {
        log.info(
                "마이홈 단지 수집을 시작합니다: pageSize={}, maxPages={}, allRegions={}, maxConcurrentRegions={}",
                request.pageSize(),
                request.maxPages(),
                request.requestsAllRegions(),
                MAX_CONCURRENT_REGIONS
        );
        List<MyHomeRegion> regions = regionsFor(request);
        IngestExecutionScope.beginWork("마이홈 단지 · 전체 지역", "지역", regions.size());
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
            MyHomeComplexCollectionReport report = regionCollector.collect(regions.getFirst(), request);
            IngestExecutionScope.workCompleted();
            return report;
        }
        return collectRegionsConcurrently(regions, request);
    }

    private MyHomeComplexCollectionReport collectRegionsConcurrently(
            List<MyHomeRegion> regions,
            MyHomeComplexCollectionRequest request
    ) {
        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_REGIONS);
        AtomicBoolean rateLimitReached = new AtomicBoolean();
        CompletionService<MyHomeComplexCollectionReport> completedTasks =
                new ExecutorCompletionService<>(executor);
        List<Future<MyHomeComplexCollectionReport>> pendingTasks = new ArrayList<>();
        Iterator<MyHomeRegion> remainingRegions = regions.iterator();
        Map<String, String> context = MDC.getCopyOfContextMap();
        while (remainingRegions.hasNext() && pendingTasks.size() < MAX_CONCURRENT_REGIONS) {
            pendingTasks.add(submit(completedTasks, remainingRegions.next(), request, rateLimitReached, context));
        }
        MyHomeComplexCollectionReport result = MyHomeComplexCollectionReport.empty();
        RuntimeException failure = null;
        boolean interrupted = false;
        boolean stopScheduling = false;
        try {
            while (!pendingTasks.isEmpty()) {
                Future<MyHomeComplexCollectionReport> completedTask = completedTasks.take();
                pendingTasks.remove(completedTask);
                try {
                    MyHomeComplexCollectionReport report = completedTask.get();
                    result = result.plus(report);
                    if (report.rateLimitedRequestCount() > 0) {
                        stopScheduling = true;
                        break;
                    }
                }
                catch (ExecutionException exception) {
                    failure = runtimeExceptionOf(exception.getCause());
                    stopScheduling = true;
                    break;
                }
                if (remainingRegions.hasNext()) {
                    pendingTasks.add(submit(
                            completedTasks, remainingRegions.next(), request, rateLimitReached, context
                    ));
                }
            }
        }
        catch (InterruptedException exception) {
            failure = interruptedFailure(exception);
            interrupted = true;
            stopScheduling = true;
        }
        finally {
            executor.shutdown();
            if (stopScheduling && failure != null && !(failure instanceof DataPipelineStoppedException)) {
                executor.shutdownNow();
            }
            InterruptedException terminationInterruption = awaitTermination(executor);
            if (terminationInterruption != null) {
                failure = appendFailure(failure, interruptedFailure(terminationInterruption));
                interrupted = true;
            }
        }
        for (Future<MyHomeComplexCollectionReport> task : pendingTasks) {
            // shutdownNow가 큐에서 꺼낸 작업은 실행되지 않으므로 완료될 수 없다.
            if (!task.isDone()) {
                task.cancel(false);
            }
            if (task.isCancelled()) {
                continue;
            }
            try {
                result = result.plus(task.get());
            }
            catch (InterruptedException exception) {
                failure = appendFailure(failure, interruptedFailure(exception));
                interrupted = true;
            }
            catch (ExecutionException exception) {
                failure = appendFailure(failure, runtimeExceptionOf(exception.getCause()));
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }

    private Future<MyHomeComplexCollectionReport> submit(
            CompletionService<MyHomeComplexCollectionReport> completedTasks,
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request,
            AtomicBoolean rateLimitReached,
            Map<String, String> context
    ) {
        return completedTasks.submit(
                IngestExecutionScope.propagate(() -> collectRegion(region, request, rateLimitReached, context))
        );
    }

    private MyHomeComplexCollectionReport collectRegion(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request,
            AtomicBoolean rateLimitReached,
            Map<String, String> context
    ) {
        try {
            if (context != null) {
                MDC.setContextMap(context);
            }
            MyHomeComplexCollectionReport report = regionCollector.collect(region, request, rateLimitReached);
            IngestExecutionScope.workCompleted();
            return report;
        }
        finally {
            MDC.clear();
        }
    }

    private InterruptedException awaitTermination(ExecutorService executor) {
        InterruptedException interruption = null;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.DAYS);
            }
            catch (InterruptedException exception) {
                executor.shutdownNow();
                if (interruption == null) {
                    interruption = exception;
                    continue;
                }
                interruption.addSuppressed(exception);
            }
        }
        return interruption;
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

    private RuntimeException interruptedFailure(InterruptedException cause) {
        return new IllegalStateException("마이홈 단지 수집이 중단되었습니다.", cause);
    }

    private RuntimeException appendFailure(
            RuntimeException primaryFailure,
            RuntimeException additionalFailure
    ) {
        if (primaryFailure == null) {
            return additionalFailure;
        }
        if (primaryFailure != additionalFailure) {
            primaryFailure.addSuppressed(additionalFailure);
        }
        return primaryFailure;
    }
}
