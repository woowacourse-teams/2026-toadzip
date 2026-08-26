package com.toadzip.backend.ingest.service;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementExternalRepository;
import com.toadzip.backend.ingest.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.repository.external.ExternalDataRequestException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class MyHomeAnnouncementCollectionService {

    private static final String LIST_POINTER = "/response/body/item";

    private final ObjectMapper objectMapper;

    private final MyHomeAnnouncementExternalRepository externalRepository;

    private final MyHomeSourceStore sourceStore;

    private final ExternalDataFailureRecorder failureRecorder;

    private final ExternalDataRetryExecutor retryExecutor;

    public MyHomeAnnouncementCollectionService(
            ObjectMapper objectMapper,
            MyHomeAnnouncementExternalRepository externalRepository,
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

    public ExternalDataCollectionReport collect(MyHomeAnnouncementCollectionRequest request) {
        log.info(
                "마이홈 공고 수집을 시작합니다: pageSize={}, maxPages={}",
                request.pageSize(),
                request.maxPages()
        );
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty("myhome-announcement");
        for (MyHomeAnnouncementSupplyType supplyType : MyHomeAnnouncementSupplyType.values()) {
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
            MyHomeAnnouncementSupplyType supplyType,
            MyHomeAnnouncementCollectionRequest request
    ) {
        ExternalDataCallCounter callCounter = new ExternalDataCallCounter();
        List<MyHomeAnnouncementSourceItem> items;
        try {
            items = fetchCompleteSupplyType(supplyType, request, callCounter);
        }
        catch (ExternalDataCallFailureException | ExternalDataRequestException exception) {
            failureRecorder.record(
                    ExternalDataSource.MYHOME_ANNOUNCEMENT,
                    request.requestDescription(supplyType, 1),
                    exception,
                    log,
                    "마이홈 공고 공급유형 수집에 실패했습니다"
            );
            return new ExternalDataCollectionReport("myhome-announcement", 0, 1, callCounter.count());
        }
        int storedRowCount = sourceStore.storeAnnouncements(items);
        return new ExternalDataCollectionReport("myhome-announcement", storedRowCount, 0, callCounter.count());
    }

    private List<MyHomeAnnouncementSourceItem> fetchCompleteSupplyType(
            MyHomeAnnouncementSupplyType supplyType,
            MyHomeAnnouncementCollectionRequest request,
            ExternalDataCallCounter callCounter
    ) {
        List<MyHomeAnnouncementSourceItem> items = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            int currentPage = page;
            String requestDescription = request.requestDescription(supplyType, currentPage);
            ExternalDataResponse response = retryExecutor.execute(
                    ExternalDataSource.MYHOME_ANNOUNCEMENT,
                    requestDescription,
                    () -> externalRepository.fetch(supplyType, request, currentPage),
                    callCounter
            );
            failureRecorder.resolve(ExternalDataSource.MYHOME_ANNOUNCEMENT, requestDescription);
            List<JsonNode> rows = DataGoKrOpenApiClient.findRows(response.body(), LIST_POINTER);
            rows.stream()
                    .map(this::sourceItemOf)
                    .forEach(items::add);
            if (collectionCompleted(response.body(), items.size(), rows.size(), request.pageSize())) {
                return items;
            }
        }
        throw new ExternalDataRequestException(
                "마이홈 공고 조회가 최대 페이지 안에 끝나지 않았습니다."
        );
    }

    private MyHomeAnnouncementSourceItem sourceItemOf(JsonNode row) {
        try {
            return objectMapper.convertValue(row, MyHomeAnnouncementSourceItem.class);
        }
        catch (RuntimeException exception) {
            throw new ExternalDataRequestException("마이홈 공고 응답 항목 형식이 올바르지 않습니다.", exception);
        }
    }

    private boolean collectionCompleted(
            JsonNode responseBody,
            int collectedCount,
            int rowCount,
            int pageSize
    ) {
        int totalCount = responseBody.at("/response/body/totalCount").asInt(-1);
        if (totalCount >= 0) {
            return collectedCount >= totalCount;
        }
        return rowCount == 0 || rowCount < pageSize;
    }
}
