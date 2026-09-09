package com.toadzip.backend.ingest.collection.service;

import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.dto.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.collection.repository.MyHomeAnnouncementCollectionExecutionLock;
import com.toadzip.backend.ingest.collection.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MyHomeAnnouncementCollectionService {

    private final MyHomeAnnouncementCollectionExecutionLock executionLock;

    private final MyHomeSourceStore sourceStore;

    private final MyHomeAnnouncementSupplyTypeCollector supplyTypeCollector;

    public MyHomeAnnouncementCollectionService(
            MyHomeAnnouncementCollectionExecutionLock executionLock,
            MyHomeSourceStore sourceStore,
            MyHomeAnnouncementSupplyTypeCollector supplyTypeCollector
    ) {
        this.executionLock = executionLock;
        this.sourceStore = sourceStore;
        this.supplyTypeCollector = supplyTypeCollector;
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
            ExternalDataCollectionReport supplyTypeReport = supplyTypeCollector.collect(
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

}
