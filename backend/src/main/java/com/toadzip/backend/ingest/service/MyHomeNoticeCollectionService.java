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
import com.toadzip.backend.ingest.dto.MyHomeNoticeCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSupplyType;
import com.toadzip.backend.ingest.repository.ExternalDataCollectionStore;
import com.toadzip.backend.ingest.repository.MyHomeNoticeExternalRepository;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;

@Slf4j
@Service
public class MyHomeNoticeCollectionService {

    private static final String LIST_POINTER = "/response/body/item";

    private final Clock clock;

    private final MyHomeNoticeExternalRepository externalRepository;

    private final ExternalDataCollectionStore store;

    private final ExternalDataFailureRecorder failureRecorder;

    public MyHomeNoticeCollectionService(
            Clock clock,
            MyHomeNoticeExternalRepository externalRepository,
            ExternalDataCollectionStore store,
            ExternalDataFailureRecorder failureRecorder
    ) {
        this.clock = clock;
        this.externalRepository = externalRepository;
        this.store = store;
        this.failureRecorder = failureRecorder;
    }

    public ExternalDataCollectionReport collect(MyHomeNoticeCollectionRequest request) {
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty("myhome-notice");
        for (MyHomeNoticeSupplyType supplyType : MyHomeNoticeSupplyType.values()) {
            report = report.plus(collectSupplyType(supplyType, request));
        }
        return report;
    }

    private ExternalDataCollectionReport collectSupplyType(
            MyHomeNoticeSupplyType supplyType,
            MyHomeNoticeCollectionRequest request
    ) {
        try {
            List<ExternalDataSnapshot> snapshots = fetchCompleteSupplyType(supplyType, request);
            store.storeSnapshots(snapshots);
            return new ExternalDataCollectionReport("myhome-notice", snapshots.size(), 0);
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    ExternalDataSource.MYHOME_NOTICE,
                    request.requestDescription(supplyType, 1),
                    exception,
                    log,
                    "마이홈 공고 공급유형 수집에 실패했습니다"
            );
            return new ExternalDataCollectionReport("myhome-notice", 0, 1);
        }
    }

    private List<ExternalDataSnapshot> fetchCompleteSupplyType(
            MyHomeNoticeSupplyType supplyType,
            MyHomeNoticeCollectionRequest request
    ) {
        List<ExternalDataSnapshot> snapshots = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            ExternalDataResponse response = externalRepository.fetch(supplyType, request, page);
            snapshots.add(ExternalDataSnapshot.create(
                    ExternalDataSource.MYHOME_NOTICE,
                    request.requestDescription(supplyType, page),
                    page,
                    clock.instant(),
                    response.rawPayload()
            ));
            int rowCount = DataGoKrOpenApiClient.findRows(response.body(), LIST_POINTER).size();
            if (rowCount == 0 || rowCount < request.pageSize()) {
                return snapshots;
            }
        }
        throw new IllegalStateException("마이홈 공고 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }
}
