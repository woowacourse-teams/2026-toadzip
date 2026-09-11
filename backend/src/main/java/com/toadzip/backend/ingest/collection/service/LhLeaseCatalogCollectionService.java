package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.domain.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.ExternalDataPage;
import com.toadzip.backend.ingest.collection.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.repository.LhLeaseCatalogExternalRepository;
import com.toadzip.backend.ingest.collection.repository.LhSourceStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.LhLeaseCatalogResponseParser;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LhLeaseCatalogCollectionService {

    private final LhLeaseCatalogExternalRepository externalRepository;

    private final LhLeaseCatalogResponseParser responseParser;

    private final LhSourceStore sourceStore;

    private final ExternalDataFailureRecorder failureRecorder;

    private final ExternalDataRetryExecutor retryExecutor;

    public ExternalDataCollectionReport collect(LhLeaseCatalogCollectionRequest request) {
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        log.info("LH 임대 카탈로그 수집을 시작합니다: pageSize={}, maxPages={}", request.pageSize(), request.maxPages());
        List<LhCatalogSourceSnapshot> snapshots;
        try {
            snapshots = fetchCompleteCatalog(request, callCounter);
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            failureRecorder.record(
                    ExternalDataSource.LH_LEASE_CATALOG,
                    request.requestDescription(1),
                    exception,
                    log,
                    "LH 임대 카탈로그 수집에 실패했습니다"
            );
            return new ExternalDataCollectionReport(
                    ExternalDataSource.LH_LEASE_CATALOG.operation(),
                    0,
                    1,
                    callCounter.count(),
                    0,
                    ExternalDataRateLimit.count(exception)
            );
        }
        int storedRowCount = sourceStore.replaceCatalog(snapshots);
        ExternalDataCollectionReport report = new ExternalDataCollectionReport(
                ExternalDataSource.LH_LEASE_CATALOG.operation(),
                storedRowCount,
                0,
                callCounter.count()
        );
        log.info(
                "LH 임대 카탈로그 수집을 완료했습니다: storedRowCount={}, externalApiCallCount={}",
                report.storedRowCount(),
                report.externalApiCallCount()
        );
        return report;
    }

    private List<LhCatalogSourceSnapshot> fetchCompleteCatalog(
            LhLeaseCatalogCollectionRequest request,
            ExternalDataCallCounter callCounter
    ) {
        List<LhCatalogSourceSnapshot> snapshots = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            int currentPage = page;
            String requestDescription = request.requestDescription(currentPage);
            ExternalDataPage<LhCatalogSourceSnapshot> parsedPage = retryExecutor.execute(
                    ExternalDataSource.LH_LEASE_CATALOG,
                    requestDescription,
                    () -> responseParser.parse(externalRepository.fetch(request, currentPage)),
                    callCounter
            );
            failureRecorder.resolve(ExternalDataSource.LH_LEASE_CATALOG, requestDescription);
            snapshots.addAll(parsedPage.items());
            if (parsedPage.completesCollection(snapshots.size(), request.pageSize())) {
                return snapshots;
            }
        }
        throw new ExternalDataRequestException("LH 임대 카탈로그 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }
}
