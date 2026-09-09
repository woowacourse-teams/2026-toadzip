package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementSourceItem;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementCollectionExecutionLock;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeAnnouncementResponseParser;
import com.toadzip.backend.ingest.collection.repository.external.MyHomeAnnouncementResponseParser.ParsedPage;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MyHomeAnnouncementCollectionService {

    private final MyHomeAnnouncementResponseParser responseParser;

    private final MyHomeAnnouncementExternalRepository externalRepository;

    private final MyHomeAnnouncementCollectionExecutionLock executionLock;

    private final MyHomeSourceStore sourceStore;

    private final ExternalDataFailureRecorder failureRecorder;

    private final ExternalDataRetryExecutor retryExecutor;

    public MyHomeAnnouncementCollectionService(
            MyHomeAnnouncementResponseParser responseParser,
            MyHomeAnnouncementExternalRepository externalRepository,
            MyHomeAnnouncementCollectionExecutionLock executionLock,
            MyHomeSourceStore sourceStore,
            ExternalDataFailureRecorder failureRecorder,
            ExternalDataRetryExecutor retryExecutor
    ) {
        this.responseParser = responseParser;
        this.externalRepository = externalRepository;
        this.executionLock = executionLock;
        this.sourceStore = sourceStore;
        this.failureRecorder = failureRecorder;
        this.retryExecutor = retryExecutor;
    }

    public ExternalDataCollectionReport collect(MyHomeAnnouncementCollectionRequest request) {
        return executionLock.tryRun(() -> collectUnlocked(request))
                .orElseThrow(this::alreadyRunning);
    }

    private ExternalDataCollectionReport collectUnlocked(MyHomeAnnouncementCollectionRequest request) {
        String runId = UUID.randomUUID().toString();
        log.info(
                "마이홈 공고 수집을 시작합니다: runId={}, pageSize={}, maxPages={}",
                runId,
                request.pageSize(),
                request.maxPages()
        );
        ExternalDataCollectionReport report = ExternalDataCollectionReport.empty("myhome-announcement");
        for (MyHomeAnnouncementSupplyType supplyType : MyHomeAnnouncementSupplyType.values()) {
            ExternalDataCollectionReport supplyTypeReport = collectSupplyType(
                    runId,
                    supplyType,
                    request
            );
            report = report.plus(supplyTypeReport);
            if (supplyTypeReport.rateLimitedRequestCount() > 0) {
                return report;
            }
        }
        if (report.failedRequestCount() == 0) {
            sourceStore.completeAnnouncementCollection(runId);
        }
        log.info(
                "마이홈 공고 수집을 완료했습니다: runId={}, storedRowCount={}, failedRequestCount={}, "
                        + "externalApiCallCount={}",
                runId,
                report.storedRowCount(),
                report.failedRequestCount(),
                report.externalApiCallCount()
        );
        return report;
    }

    private IngestAlreadyRunningException alreadyRunning() {
        log.warn("마이홈 공고 수집이 이미 실행 중이므로 중복 실행을 건너뜁니다.");
        return new IngestAlreadyRunningException("마이홈 공고 수집이 이미 실행 중입니다.");
    }

    private ExternalDataCollectionReport collectSupplyType(
            String runId,
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
            return new ExternalDataCollectionReport(
                    "myhome-announcement",
                    0,
                    1,
                    callCounter.count(),
                    0,
                    ExternalDataRateLimit.count(exception)
            );
        }
        int storedRowCount = sourceStore.storeAnnouncements(runId, items);
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
            ParsedPage parsedPage = responseParser.parse(response);
            items.addAll(parsedPage.items());
            if (parsedPage.completesCollection(items.size(), request.pageSize())) {
                return items;
            }
        }
        throw new ExternalDataRequestException(
                "마이홈 공고 조회가 최대 페이지 안에 끝나지 않았습니다."
        );
    }

}
