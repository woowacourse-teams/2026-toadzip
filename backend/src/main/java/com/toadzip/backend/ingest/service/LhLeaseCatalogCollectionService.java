package com.toadzip.backend.ingest.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalApiData;
import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.repository.ExternalApiCollectionStore;
import com.toadzip.backend.ingest.repository.LhLeaseCatalogApiRepository;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;

@Slf4j
@Service
public class LhLeaseCatalogCollectionService {

    private static final String LIST_KEY = "dsList";

    private final Clock clock;

    private final LhLeaseCatalogApiRepository apiRepository;

    private final ExternalApiCollectionStore store;

    private final ExternalApiFailureRecorder failureRecorder;

    public LhLeaseCatalogCollectionService(
            Clock clock,
            LhLeaseCatalogApiRepository apiRepository,
            ExternalApiCollectionStore store,
            ExternalApiFailureRecorder failureRecorder
    ) {
        this.clock = clock;
        this.apiRepository = apiRepository;
        this.store = store;
        this.failureRecorder = failureRecorder;
    }

    public ExternalApiCollectionReport collect(LhLeaseCatalogCollectionRequest request) {
        try {
            List<ExternalApiData> apiData = fetchCompleteCatalog(request);
            store.storeApiData(apiData);
            return new ExternalApiCollectionReport("lh-lease-catalog", apiData.size(), 0);
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

    private List<ExternalApiData> fetchCompleteCatalog(LhLeaseCatalogCollectionRequest request) {
        List<ExternalApiData> apiData = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            ExternalApiResponse response = apiRepository.fetch(request, page);
            apiData.add(ExternalApiData.create(
                    ExternalApi.LH_LEASE_CATALOG,
                    request.requestDescription(page),
                    page,
                    clock.instant(),
                    response.apiData()
            ));
            int rowCount = DataGoKrOpenApiClient.findRows(response.responseBody(), LIST_KEY).size();
            if (rowCount < request.pageSize()) {
                return apiData;
            }
        }
        throw new IllegalStateException("LH 임대 카탈로그 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }
}
