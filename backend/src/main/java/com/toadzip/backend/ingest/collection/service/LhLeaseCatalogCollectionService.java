package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhCatalogSourceItem;
import com.toadzip.backend.ingest.collection.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.repository.LhLeaseCatalogExternalRepository;
import com.toadzip.backend.ingest.collection.repository.LhSourceStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.LhLeaseCatalogResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.LhLeaseCatalogResponseParser.ParsedPage;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LhLeaseCatalogCollectionService {

    private final LhLeaseCatalogExternalRepository externalRepository;

    private final LhLeaseCatalogResponseParser responseParser;

    private final LhSourceStore sourceStore;

    private final ExternalDataFailureRecorder failureRecorder;

    private final ExternalDataRetryExecutor retryExecutor;

    public LhLeaseCatalogCollectionService(
            LhLeaseCatalogExternalRepository externalRepository,
            LhLeaseCatalogResponseParser responseParser,
            LhSourceStore sourceStore,
            ExternalDataFailureRecorder failureRecorder,
            ExternalDataRetryExecutor retryExecutor
    ) {
        this.externalRepository = externalRepository;
        this.responseParser = responseParser;
        this.sourceStore = sourceStore;
        this.failureRecorder = failureRecorder;
        this.retryExecutor = retryExecutor;
    }

    public ExternalDataCollectionReport collect(LhLeaseCatalogCollectionRequest request) {
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        log.info("LH 임대 카탈로그 수집을 시작합니다: pageSize={}, maxPages={}", request.pageSize(), request.maxPages());
        List<LhCatalogSourceItem> items;
        try {
            items = fetchCompleteCatalog(request, callCounter);
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
                    "lh-lease-catalog",
                    0,
                    1,
                    callCounter.count(),
                    0,
                    ExternalDataRateLimit.count(exception)
            );
        }
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
            ParsedPage parsedPage = responseParser.parse(response);
            items.addAll(parsedPage.items());
            if (parsedPage.completesCollection(request.pageSize())) {
                return items;
            }
        }
        throw new ExternalDataRequestException("LH 임대 카탈로그 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }
}
