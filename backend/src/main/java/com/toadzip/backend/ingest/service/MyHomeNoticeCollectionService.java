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
import com.toadzip.backend.ingest.dto.MyHomeNoticeCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSupplyType;
import com.toadzip.backend.ingest.repository.ExternalApiCollectionStore;
import com.toadzip.backend.ingest.repository.MyHomeNoticeApiRepository;
import com.toadzip.backend.ingest.repository.external.DataGoKrOpenApiClient;

@Slf4j
@Service
public class MyHomeNoticeCollectionService {

    private static final String LIST_POINTER = "/response/body/item";

    private final Clock clock;

    private final MyHomeNoticeApiRepository apiRepository;

    private final ExternalApiCollectionStore store;

    private final ExternalApiFailureRecorder failureRecorder;

    private final ExternalApiRetryExecutor retryExecutor;

    public MyHomeNoticeCollectionService(
            Clock clock,
            MyHomeNoticeApiRepository apiRepository,
            ExternalApiCollectionStore store,
            ExternalApiFailureRecorder failureRecorder,
            ExternalApiRetryExecutor retryExecutor
    ) {
        this.clock = clock;
        this.apiRepository = apiRepository;
        this.store = store;
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
            List<ExternalApiData> apiData = fetchCompleteSupplyType(supplyType, request, callCounter);
            store.storeApiData(apiData);
            return new ExternalApiCollectionReport("myhome-notice", apiData.size(), 0);
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

    private List<ExternalApiData> fetchCompleteSupplyType(
            MyHomeNoticeSupplyType supplyType,
            MyHomeNoticeCollectionRequest request,
            ExternalApiCallCounter callCounter
    ) {
        List<ExternalApiData> apiData = new ArrayList<>();
        for (int page = 1; page <= request.maxPages(); page++) {
            int currentPage = page;
            String requestDescription = request.requestDescription(supplyType, currentPage);
            ExternalApiResponse response = retryExecutor.execute(
                    ExternalApi.MYHOME_NOTICE,
                    requestDescription,
                    () -> apiRepository.fetch(supplyType, request, currentPage),
                    callCounter
            );
            apiData.add(ExternalApiData.create(
                    ExternalApi.MYHOME_NOTICE,
                    request.requestDescription(supplyType, page),
                    page,
                    clock.instant(),
                    response.apiData()
            ));
            int rowCount = DataGoKrOpenApiClient.findRows(response.responseBody(), LIST_POINTER).size();
            if (rowCount == 0 || rowCount < request.pageSize()) {
                return apiData;
            }
        }
        throw new IllegalStateException("마이홈 공고 조회가 최대 페이지 안에 끝나지 않았습니다.");
    }
}
