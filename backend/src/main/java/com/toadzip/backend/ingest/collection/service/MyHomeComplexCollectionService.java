package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.collection.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeRegion;
import com.toadzip.backend.ingest.collection.repository.MyHomeRegionCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MyHomeComplexCollectionService {

    private static final int MAX_CONCURRENT_REGIONS = 4;

    private final MyHomeRegionCatalog regionCatalog;

    private final MyHomeComplexRegionCollector regionCollector;

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
        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_REGIONS);
        AtomicBoolean rateLimitReached = new AtomicBoolean();
        BlockingQueue<Future<MyHomeComplexCollectionReport>> completedTasks =
                new LinkedBlockingQueue<>();
        List<FutureTask<MyHomeComplexCollectionReport>> tasks = submit(
                executor,
                completedTasks,
                regions,
                request,
                rateLimitReached
        );
        List<MyHomeComplexCollectionReport> reports = new ArrayList<>();
        RuntimeException failure = null;
        boolean interrupted = false;
        int completedTaskCount = 0;
        boolean cancelRemaining = false;
        try {
            while (completedTaskCount < tasks.size()) {
                Future<MyHomeComplexCollectionReport> completedTask = completedTasks.take();
                completedTaskCount++;
                try {
                    MyHomeComplexCollectionReport report = completedTask.get();
                    reports.add(report);
                    if (report.rateLimitedRequestCount() > 0) {
                        cancelRemaining = true;
                        break;
                    }
                }
                catch (ExecutionException exception) {
                    failure = runtimeExceptionOf(exception.getCause());
                    cancelRemaining = true;
                    break;
                }
            }
        }
        catch (InterruptedException exception) {
            failure = interruptedFailure(exception);
            interrupted = true;
            cancelRemaining = true;
        }
        finally {
            if (cancelRemaining) {
                cancelNeverStarted(executor.shutdownNow());
            }
            else {
                executor.shutdown();
            }
            InterruptedException terminationInterruption = awaitTermination(executor);
            if (terminationInterruption != null) {
                failure = appendFailure(failure, interruptedFailure(terminationInterruption));
                interrupted = true;
            }
        }
        while (completedTaskCount < tasks.size()) {
            Future<MyHomeComplexCollectionReport> completedTask = completedTasks.remove();
            completedTaskCount++;
            if (completedTask.isCancelled()) {
                continue;
            }
            try {
                reports.add(completedTask.get());
            }
            catch (InterruptedException exception) {
                failure = appendFailure(failure, interruptedFailure(exception));
                interrupted = true;
            }
            catch (ExecutionException exception) {
                RuntimeException additionalFailure = runtimeExceptionOf(exception.getCause());
                failure = appendFailure(failure, additionalFailure);
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
        return reports.stream()
                .reduce(MyHomeComplexCollectionReport.empty(), MyHomeComplexCollectionReport::plus);
    }

    private List<FutureTask<MyHomeComplexCollectionReport>> submit(
            ExecutorService executor,
            BlockingQueue<Future<MyHomeComplexCollectionReport>> completedTasks,
            List<MyHomeRegion> regions,
            MyHomeComplexCollectionRequest request,
            AtomicBoolean rateLimitReached
    ) {
        List<FutureTask<MyHomeComplexCollectionReport>> tasks = new ArrayList<>();
        for (MyHomeRegion region : regions) {
            FutureTask<MyHomeComplexCollectionReport> task = new FutureTask<>(
                    () -> regionCollector.collect(region, request, rateLimitReached)
            ) {
                @Override
                protected void done() {
                    completedTasks.add(this);
                }
            };
            tasks.add(task);
            executor.execute(task);
        }
        return tasks;
    }

    private void cancelNeverStarted(List<Runnable> neverStartedTasks) {
        for (Runnable neverStartedTask : neverStartedTasks) {
            if (neverStartedTask instanceof Future<?> future) {
                future.cancel(false);
            }
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
                }
                else {
                    interruption.addSuppressed(exception);
                }
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
        primaryFailure.addSuppressed(additionalFailure);
        return primaryFailure;
    }
}
