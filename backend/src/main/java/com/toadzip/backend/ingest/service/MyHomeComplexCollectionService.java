package com.toadzip.backend.ingest.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalApiData;
import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionReport;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeRegion;
import com.toadzip.backend.ingest.repository.ExternalApiCollectionStore;
import com.toadzip.backend.ingest.repository.MyHomeComplexApiRepository;
import com.toadzip.backend.ingest.repository.MyHomeRegionCatalog;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;

@Slf4j
@Service
public class MyHomeComplexCollectionService {

    private static final String LIST_POINTER = "/response/body/item";

    private static final int MAX_CONCURRENT_REGIONS = 4;

    private final Clock clock;

    private final MyHomeComplexApiRepository apiRepository;

    private final MyHomeRegionCatalog regionCatalog;

    private final ExternalApiCollectionStore store;

    private final ExternalApiFailureRecorder failureRecorder;

    private final ExternalApiRetryExecutor retryExecutor;

    public MyHomeComplexCollectionService(
            Clock clock,
            MyHomeComplexApiRepository apiRepository,
            MyHomeRegionCatalog regionCatalog,
            ExternalApiCollectionStore store,
            ExternalApiFailureRecorder failureRecorder,
            ExternalApiRetryExecutor retryExecutor
    ) {
        this.clock = clock;
        this.apiRepository = apiRepository;
        this.regionCatalog = regionCatalog;
        this.store = store;
        this.failureRecorder = failureRecorder;
        this.retryExecutor = retryExecutor;
    }

    public MyHomeComplexCollectionReport collect(MyHomeComplexCollectionRequest request) {
        List<MyHomeRegion> regions = regionsFor(request);
        MyHomeComplexCollectionReport report = collectRegions(regions, request);
        log.info(
                "마이홈 단지 수집을 완료했습니다: storedApiDataCount={}, failedRequestCount={}, externalApiCallCount={}",
                report.storedApiDataCount(),
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
        try {
            List<Future<MyHomeComplexCollectionReport>> futures = regions.stream()
                    .map(region -> executor.submit(() -> collectRegion(region, request)))
                    .toList();
            for (Future<MyHomeComplexCollectionReport> future : futures) {
                report = report.plus(await(future, request));
            }
            return report;
        }
        finally {
            executor.shutdown();
        }
    }

    private MyHomeComplexCollectionReport await(
            Future<MyHomeComplexCollectionReport> future,
            MyHomeComplexCollectionRequest request
    ) {
        try {
            return future.get();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("마이홈 단지 수집이 중단되었습니다.", exception);
        }
        catch (ExecutionException exception) {
            RuntimeException cause = runtimeExceptionOf(exception.getCause());
            failureRecorder.record(
                    ExternalApi.MYHOME_COMPLEX,
                    request.requestDescription(regionCatalog.findAll().getFirst(), 1),
                    cause,
                    log,
                    "마이홈 단지 지역 수집에 실패했습니다"
            );
            return new MyHomeComplexCollectionReport("myhome-complex", 0, 1, 0);
        }
    }

    private MyHomeComplexCollectionReport collectRegion(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request
    ) {
        ExternalApiCallCounter callCounter = new ExternalApiCallCounter();
        try {
            List<ExternalApiData> apiData = fetchCompleteRegion(region, request, callCounter);
            store.storeApiData(apiData);
            return new MyHomeComplexCollectionReport("myhome-complex", apiData.size(), 0, callCounter.count());
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    ExternalApi.MYHOME_COMPLEX,
                    request.requestDescription(region, 1),
                    exception,
                    log,
                    "마이홈 단지 지역 수집에 실패했습니다"
            );
            return new MyHomeComplexCollectionReport("myhome-complex", 0, 1, callCounter.count());
        }
    }

    private List<ExternalApiData> fetchCompleteRegion(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request,
            ExternalApiCallCounter callCounter
    ) {
        List<ExternalApiData> apiData = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            int currentPage = page;
            String requestDescription = request.requestDescription(region, currentPage);
            ExternalApiResponse response = retryExecutor.execute(
                    ExternalApi.MYHOME_COMPLEX,
                    requestDescription,
                    () -> apiRepository.fetch(region, request, currentPage),
                    callCounter
            );
            apiData.add(ExternalApiData.create(
                    ExternalApi.MYHOME_COMPLEX,
                    request.requestDescription(region, page),
                    page,
                    clock.instant(),
                    response.apiData()
            ));
            int rowCount = DataGoKrOpenApiClient.findRows(response.responseBody(), LIST_POINTER).size();
            if (rowCount < request.pageSize()) {
                return apiData;
            }
        }
        throw new IllegalStateException("마이홈 단지 조회가 최대 페이지 안에 끝나지 않았습니다.");
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
