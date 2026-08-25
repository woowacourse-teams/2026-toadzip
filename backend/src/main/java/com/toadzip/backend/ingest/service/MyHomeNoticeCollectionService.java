package com.toadzip.backend.ingest.service;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.MyHomeNoticeCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSupplyType;
import com.toadzip.backend.ingest.repository.MyHomeNoticeApiRepository;
import com.toadzip.backend.ingest.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class MyHomeNoticeCollectionService {

    private static final String LIST_POINTER = "/response/body/item";

    private final ObjectMapper objectMapper;

    private final MyHomeNoticeApiRepository apiRepository;

    private final MyHomeSourceStore sourceStore;

    private final ExternalApiFailureRecorder failureRecorder;

    private final ExternalApiRetryExecutor retryExecutor;

    public MyHomeNoticeCollectionService(
            ObjectMapper objectMapper,
            MyHomeNoticeApiRepository apiRepository,
            MyHomeSourceStore sourceStore,
            ExternalApiFailureRecorder failureRecorder,
            ExternalApiRetryExecutor retryExecutor
    ) {
        this.objectMapper = objectMapper;
        this.apiRepository = apiRepository;
        this.sourceStore = sourceStore;
        this.failureRecorder = failureRecorder;
        this.retryExecutor = retryExecutor;
    }

    public ExternalApiCollectionReport collect(MyHomeNoticeCollectionRequest request) {
        ExternalApiCollectionReport report = ExternalApiCollectionReport.empty("myhome-notice");
        for (MyHomeNoticeSupplyType supplyType : MyHomeNoticeSupplyType.values()) {
            report = report.plus(collectSupplyType(supplyType, request));
        }
        return report;
    }

    private ExternalApiCollectionReport collectSupplyType(
            MyHomeNoticeSupplyType supplyType,
            MyHomeNoticeCollectionRequest request
    ) {
        ExternalApiCallCounter callCounter = new ExternalApiCallCounter();
        try {
            List<MyHomeNoticeSourceItem> items = fetchCompleteSupplyType(supplyType, request, callCounter);
            int storedRowCount = sourceStore.storeNotices(items);
            return new ExternalApiCollectionReport("myhome-notice", storedRowCount, 0);
        }
        catch (RuntimeException exception) {
            failureRecorder.record(
                    ExternalApi.MYHOME_NOTICE,
                    request.requestDescription(supplyType, 1),
                    exception,
                    log,
                    "마이홈 공고 공급유형 수집에 실패했습니다"
            );
            return new ExternalApiCollectionReport("myhome-notice", 0, 1);
        }
    }

    private List<MyHomeNoticeSourceItem> fetchCompleteSupplyType(
            MyHomeNoticeSupplyType supplyType,
            MyHomeNoticeCollectionRequest request,
            ExternalApiCallCounter callCounter
    ) {
        List<MyHomeNoticeSourceItem> items = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            int currentPage = page;
            String requestDescription = request.requestDescription(supplyType, currentPage);
            ExternalApiResponse response = retryExecutor.execute(
                    ExternalApi.MYHOME_NOTICE,
                    requestDescription,
                    () -> apiRepository.fetch(supplyType, request, currentPage),
                    callCounter
            );
            List<JsonNode> rows = DataGoKrOpenApiClient.findRows(response.responseBody(), LIST_POINTER);
            rows.stream()
                    .map(row -> objectMapper.convertValue(row, MyHomeNoticeSourceItem.class))
                    .forEach(items::add);
            int rowCount = rows.size();
            if (rowCount == 0 || rowCount < request.pageSize()) {
                return items;
            }
        }
        throw new IllegalStateException("마이홈 공고 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }
}
