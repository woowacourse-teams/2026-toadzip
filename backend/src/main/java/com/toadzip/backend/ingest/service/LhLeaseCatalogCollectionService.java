package com.toadzip.backend.ingest.service;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.LhCatalogSourceItem;
import com.toadzip.backend.ingest.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.repository.LhLeaseCatalogExternalRepository;
import com.toadzip.backend.ingest.repository.LhSourceStore;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;
import tools.jackson.databind.JsonNode;

@Slf4j
@Service
public class LhLeaseCatalogCollectionService {

    private static final String LIST_KEY = "dsList";

    private final LhLeaseCatalogExternalRepository externalRepository;

    private final LhSourceStore sourceStore;

    private final ExternalDataFailureRecorder failureRecorder;

    private final ExternalDataRetryExecutor retryExecutor;

    public LhLeaseCatalogCollectionService(
            LhLeaseCatalogExternalRepository externalRepository,
            LhSourceStore sourceStore,
            ExternalDataFailureRecorder failureRecorder,
            ExternalDataRetryExecutor retryExecutor
    ) {
        this.externalRepository = externalRepository;
        this.sourceStore = sourceStore;
        this.failureRecorder = failureRecorder;
        this.retryExecutor = retryExecutor;
    }

    public ExternalDataCollectionReport collect(LhLeaseCatalogCollectionRequest request) {
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        log.info("LH 임대 카탈로그 수집을 시작합니다: pageSize={}, maxPages={}", request.pageSize(), request.maxPages());
        try {
            List<LhCatalogSourceItem> items = fetchCompleteCatalog(request, callCounter);
            int storedRowCount = sourceStore.replaceCatalog(items);
            ExternalDataCollectionReport report = new ExternalDataCollectionReport(
                    "lh-lease-catalog",
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
        catch (RuntimeException exception) {
            failureRecorder.record(
                    ExternalDataSource.LH_LEASE_CATALOG,
                    request.requestDescription(1),
                    exception,
                    log,
                    "LH 임대 카탈로그 수집에 실패했습니다"
            );
            return new ExternalDataCollectionReport("lh-lease-catalog", 0, 1, callCounter.count());
        }
    }

    private List<LhCatalogSourceItem> fetchCompleteCatalog(
            LhLeaseCatalogCollectionRequest request,
            ExternalDataCallCounter callCounter
    ) {
        List<LhCatalogSourceItem> items = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            int currentPage = page;
            String requestDescription = request.requestDescription(currentPage);
            ExternalDataResponse response = retryExecutor.execute(
                    ExternalDataSource.LH_LEASE_CATALOG,
                    requestDescription,
                    () -> externalRepository.fetch(request, currentPage),
                    callCounter
            );
            failureRecorder.resolve(ExternalDataSource.LH_LEASE_CATALOG, requestDescription);
            List<JsonNode> rows = DataGoKrOpenApiClient.findRows(response.body(), LIST_KEY);
            rows.stream().map(LhCatalogSourceItem::from).forEach(items::add);
            int rowCount = rows.size();
            if (rowCount < request.pageSize()) {
                return items;
            }
        }
        throw new IllegalStateException("LH 임대 카탈로그 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }
}
