package com.toadzip.backend.ingest.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataSnapshot;
import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.repository.ExternalDataCollectionStore;
import com.toadzip.backend.ingest.repository.LhLeaseCatalogExternalRepository;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;

@Slf4j
@Service
public class LhLeaseCatalogCollectionService {

    private static final String LIST_KEY = "dsList";

    private final Clock clock;

    private final LhLeaseCatalogExternalRepository externalRepository;

    private final ExternalDataCollectionStore store;

    private final ExternalDataFailureRecorder failureRecorder;

    public LhLeaseCatalogCollectionService(
            Clock clock,
            LhLeaseCatalogExternalRepository externalRepository,
            ExternalDataCollectionStore store,
            ExternalDataFailureRecorder failureRecorder
    ) {
        this.clock = clock;
        this.externalRepository = externalRepository;
        this.store = store;
        this.failureRecorder = failureRecorder;
    }

    public ExternalDataCollectionReport collect(LhLeaseCatalogCollectionRequest request) {
        try {
            List<ExternalDataSnapshot> snapshots = fetchCompleteCatalog(request);
            store.storeSnapshots(snapshots);
            return new ExternalDataCollectionReport("lh-lease-catalog", snapshots.size(), 0);
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    ExternalDataSource.LH_LEASE_CATALOG,
                    request.requestDescription(1),
                    exception,
                    log,
                    "LH 임대 카탈로그 수집에 실패했습니다"
            );
            return new ExternalDataCollectionReport("lh-lease-catalog", 0, 1);
        }
    }

    private List<ExternalDataSnapshot> fetchCompleteCatalog(LhLeaseCatalogCollectionRequest request) {
        List<ExternalDataSnapshot> snapshots = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            ExternalDataResponse response = externalRepository.fetch(request, page);
            snapshots.add(ExternalDataSnapshot.create(
                    ExternalDataSource.LH_LEASE_CATALOG,
                    request.requestDescription(page),
                    page,
                    clock.instant(),
                    response.rawPayload()
            ));
            int rowCount = DataGoKrOpenApiClient.findRows(response.body(), LIST_KEY).size();
            if (rowCount < request.pageSize()) {
                return snapshots;
            }
        }
        throw new IllegalStateException("LH 임대 카탈로그 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }
}
