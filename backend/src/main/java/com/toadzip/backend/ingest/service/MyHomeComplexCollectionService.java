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

import com.toadzip.backend.ingest.domain.ExternalDataSnapshot;
import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.MyHomeComplexCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeRegion;
import com.toadzip.backend.ingest.repository.ExternalDataCollectionStore;
import com.toadzip.backend.ingest.repository.MyHomeComplexExternalRepository;
import com.toadzip.backend.ingest.repository.MyHomeRegionCatalog;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;

@Slf4j
@Service
public class MyHomeComplexCollectionService {

    private static final String LIST_POINTER = "/response/body/item";

    private static final int MAX_CONCURRENT_REGIONS = 4;

    private final Clock clock;

    private final MyHomeComplexExternalRepository externalRepository;

    private final MyHomeRegionCatalog regionCatalog;

    private final ExternalDataCollectionStore store;

    private final ExternalDataFailureRecorder failureRecorder;

    public MyHomeComplexCollectionService(
            Clock clock,
            MyHomeComplexExternalRepository externalRepository,
            MyHomeRegionCatalog regionCatalog,
            ExternalDataCollectionStore store,
            ExternalDataFailureRecorder failureRecorder
    ) {
        this.clock = clock;
        this.externalRepository = externalRepository;
        this.regionCatalog = regionCatalog;
        this.store = store;
        this.failureRecorder = failureRecorder;
    }

    public ExternalDataCollectionReport collect(MyHomeComplexCollectionRequest request) {
        List<MyHomeRegion> regions = regionsFor(request);
        if (regions.size() == 1) {
            return collectRegion(regions.getFirst(), request);
        }
        return collectRegionsConcurrently(regions, request);
    }

    private ExternalDataCollectionReport collectRegionsConcurrently(
            List<MyHomeRegion> regions,
            MyHomeComplexCollectionRequest request
    ) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty("myhome-complex");
        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_REGIONS);
        try {
            List<Future<ExternalDataCollectionReport>> futures = regions.stream()
                    .map(region -> executor.submit(() -> collectRegion(region, request)))
                    .toList();
            for (Future<ExternalDataCollectionReport> future : futures) {
                report = report.plus(await(future, request));
            }
            return report;
        }
        finally {
            executor.shutdown();
        }
    }

    private ExternalDataCollectionReport await(
            Future<ExternalDataCollectionReport> future,
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
                    ExternalDataSource.MYHOME_COMPLEX,
                    request.requestDescription(regionCatalog.findAll().getFirst(), 1),
                    cause,
                    log,
                    "마이홈 단지 지역 수집에 실패했습니다"
            );
            return new ExternalDataCollectionReport("myhome-complex", 0, 1);
        }
    }

    private ExternalDataCollectionReport collectRegion(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request
    ) {
        try {
            List<ExternalDataSnapshot> snapshots = fetchCompleteRegion(region, request);
            store.storeSnapshots(snapshots);
            return new ExternalDataCollectionReport("myhome-complex", snapshots.size(), 0);
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    ExternalDataSource.MYHOME_COMPLEX,
                    request.requestDescription(region, 1),
                    exception,
                    log,
                    "마이홈 단지 지역 수집에 실패했습니다"
            );
            return new ExternalDataCollectionReport("myhome-complex", 0, 1);
        }
    }

    private List<ExternalDataSnapshot> fetchCompleteRegion(
            MyHomeRegion region,
            MyHomeComplexCollectionRequest request
    ) {
        List<ExternalDataSnapshot> snapshots = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            ExternalDataResponse response = externalRepository.fetch(region, request, page);
            snapshots.add(ExternalDataSnapshot.create(
                    ExternalDataSource.MYHOME_COMPLEX,
                    request.requestDescription(region, page),
                    page,
                    clock.instant(),
                    response.rawPayload()
            ));
            int rowCount = DataGoKrOpenApiClient.findRows(response.body(), LIST_POINTER).size();
            if (rowCount < request.pageSize()) {
                return snapshots;
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
