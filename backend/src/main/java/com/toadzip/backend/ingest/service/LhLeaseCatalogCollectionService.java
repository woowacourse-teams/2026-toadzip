package com.toadzip.backend.ingest.service;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.LhCatalogSourceItem;
import com.toadzip.backend.ingest.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.repository.LhLeaseCatalogApiRepository;
import com.toadzip.backend.ingest.repository.LhSourceStore;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;
import tools.jackson.databind.JsonNode;

@Slf4j
@Service
public class LhLeaseCatalogCollectionService {

    private static final String LIST_KEY = "dsList";

    private final LhLeaseCatalogApiRepository apiRepository;

    private final LhSourceStore sourceStore;

    private final ExternalApiFailureRecorder failureRecorder;

    public LhLeaseCatalogCollectionService(
            LhLeaseCatalogApiRepository apiRepository,
            LhSourceStore sourceStore,
            ExternalApiFailureRecorder failureRecorder
    ) {
        this.apiRepository = apiRepository;
        this.sourceStore = sourceStore;
        this.failureRecorder = failureRecorder;
    }

    public ExternalApiCollectionReport collect(LhLeaseCatalogCollectionRequest request) {
        try {
            List<LhCatalogSourceItem> items = fetchCompleteCatalog(request);
            int storedRowCount = sourceStore.replaceCatalog(items);
            return new ExternalApiCollectionReport("lh-lease-catalog", storedRowCount, 0);
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    ExternalApi.LH_LEASE_CATALOG,
                    request.requestDescription(1),
                    exception,
                    log,
                    "LH 임대 카탈로그 수집에 실패했습니다"
            );
            return new ExternalApiCollectionReport("lh-lease-catalog", 0, 1);
        }
    }

    private List<LhCatalogSourceItem> fetchCompleteCatalog(LhLeaseCatalogCollectionRequest request) {
        List<LhCatalogSourceItem> items = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            ExternalApiResponse response = apiRepository.fetch(request, page);
            List<JsonNode> rows = DataGoKrOpenApiClient.findRows(response.responseBody(), LIST_KEY);
            rows.stream().map(LhCatalogSourceItem::from).forEach(items::add);
            int rowCount = rows.size();
            if (rowCount < request.pageSize()) {
                return items;
            }
        }
        throw new IllegalStateException("LH 임대 카탈로그 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }
}
