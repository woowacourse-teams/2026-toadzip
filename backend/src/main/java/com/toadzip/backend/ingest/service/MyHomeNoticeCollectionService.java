package com.toadzip.backend.ingest.service;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.MyHomeNoticeCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSupplyType;
import com.toadzip.backend.ingest.repository.MyHomeNoticeExternalRepository;
import com.toadzip.backend.ingest.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class MyHomeNoticeCollectionService {

    private static final String LIST_POINTER = "/response/body/item";

    private final ObjectMapper objectMapper;

    private final MyHomeNoticeExternalRepository externalRepository;

    private final MyHomeSourceStore sourceStore;

    private final ExternalDataFailureRecorder failureRecorder;

    private final ExternalDataRetryExecutor retryExecutor;

    public MyHomeNoticeCollectionService(
            ObjectMapper objectMapper,
            MyHomeNoticeExternalRepository externalRepository,
            MyHomeSourceStore sourceStore,
            ExternalDataFailureRecorder failureRecorder,
            ExternalDataRetryExecutor retryExecutor
    ) {
        this.objectMapper = objectMapper;
        this.externalRepository = externalRepository;
        this.sourceStore = sourceStore;
        this.failureRecorder = failureRecorder;
        this.retryExecutor = retryExecutor;
    }

    public ExternalDataCollectionReport collect(MyHomeNoticeCollectionRequest request) {
        log.info("마이홈 공고 수집을 시작합니다: pageSize={}, maxPages={}", request.pageSize(), request.maxPages());
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty("myhome-notice");
        for (MyHomeNoticeSupplyType supplyType : MyHomeNoticeSupplyType.values()) {
            report = report.plus(collectSupplyType(supplyType, request));
        }
        log.info(
                "마이홈 공고 수집을 완료했습니다: storedRowCount={}, failedRequestCount={}, "
                        + "externalApiCallCount={}",
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount()
        );
        return report;
    }

    private ExternalDataCollectionReport collectSupplyType(
            MyHomeNoticeSupplyType supplyType,
            MyHomeNoticeCollectionRequest request
    ) {
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        try {
            List<MyHomeNoticeSourceItem> items = fetchCompleteSupplyType(supplyType, request, callCounter);
            int storedRowCount = sourceStore.storeNotices(items);
            return new ExternalDataCollectionReport("myhome-notice", storedRowCount, 0, callCounter.count());
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    ExternalDataSource.MYHOME_NOTICE,
                    request.requestDescription(supplyType, 1),
                    exception,
                    log,
                    "마이홈 공고 공급유형 수집에 실패했습니다"
            );
            return new ExternalDataCollectionReport("myhome-notice", 0, 1, callCounter.count());
        }
    }

    private List<MyHomeNoticeSourceItem> fetchCompleteSupplyType(
            MyHomeNoticeSupplyType supplyType,
            MyHomeNoticeCollectionRequest request,
            ExternalDataCallCounter callCounter
    ) {
        List<MyHomeNoticeSourceItem> items = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            int currentPage = page;
            String requestDescription = request.requestDescription(supplyType, currentPage);
            ExternalDataResponse response = retryExecutor.execute(
                    ExternalDataSource.MYHOME_NOTICE,
                    requestDescription,
                    () -> externalRepository.fetch(supplyType, request, currentPage),
                    callCounter
            );
            failureRecorder.resolve(ExternalDataSource.MYHOME_NOTICE, requestDescription);
            List<JsonNode> rows = DataGoKrOpenApiClient.findRows(response.body(), LIST_POINTER);
            rows.stream()
                    .map(row -> objectMapper.convertValue(row, MyHomeNoticeSourceItem.class))
                    .forEach(items::add);
            if (collectionCompleted(response.body(), items.size(), rows.size(), request.pageSize())) {
                return items;
            }
        }
        throw new IllegalStateException("마이홈 공고 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }

    private boolean collectionCompleted(JsonNode responseBody, int collectedCount, int rowCount, int pageSize) {
        int totalCount = responseBody.at("/response/body/totalCount").asInt(-1);
        if (totalCount >= 0) {
            return collectedCount >= totalCount;
        }
        return rowCount == 0 || rowCount < pageSize;
    }
}
