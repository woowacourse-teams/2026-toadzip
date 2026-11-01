package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.api.MyHomeAnnouncementCollectionCommand;
import com.toadzip.backend.ingest.collection.paging.service.PagedSourceCollector;
import com.toadzip.backend.ingest.collection.service.ExternalDataCallCounter;
import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MyHomeAnnouncementCollectionService {

    private final MyHomeAnnouncementCollector collector;
    private final MyHomeAnnouncementLifecycleService lifecycle;
    private final PagedSourceCollector pagedCollector;

    public ExternalDataCollectionReport collect(MyHomeAnnouncementCollectionCommand request) {
        IngestExecutionScope.requireHeld();
        UUID runId = IngestExecutionScope.currentExecutionId().orElseGet(UUID::randomUUID);
        var report = ExternalDataCollectionReport.empty(ExternalDataSource.MYHOME_ANNOUNCEMENT.operation());
        for (MyHomeAnnouncementSupplyType type : MyHomeAnnouncementSupplyType.values()) {
            IngestExecutionScope.beginWork("마이홈 공고 · " + type.requestCode(), "페이지", -1);
            var result = pagedCollector.collectAndReport(collector, startedAt -> new MyHomeAnnouncementCollectionRequest(
                    runId, type.requestCode(), request.pageSize(), request.maxPages(), startedAt));
            report = report.plus(result);
            if (result.rateLimitedRequestCount() > 0) {
                return report;
            }
        }
        if (report.failedRequestCount() == 0) {
            lifecycle.completeRun(runId);
        }
        return report;
    }

    /** 목록의 모든 페이지를 검증하지만 해당 공고만 저장한다. 실패를 기록하지 않고 전체 수집 완료 처리도 하지 않는다. */
    public int collectAnnouncement(String pblancId) {
        IngestExecutionScope.requireHeld();
        UUID executionId = IngestExecutionScope.currentExecutionId().orElseGet(UUID::randomUUID);
        var counter = new ExternalDataCallCounter();
        int stored = 0;
        for (MyHomeAnnouncementSupplyType type : MyHomeAnnouncementSupplyType.values()) {
            stored += pagedCollector.collect(collector, startedAt -> new MyHomeAnnouncementCollectionRequest(
                    executionId, type.requestCode(), 500, 1000, startedAt, pblancId), counter);
        }
        if (stored == 0) {
            throw new InvalidIngestRequestException("공고를 찾을 수 없습니다: " + pblancId);
        }
        return stored;
    }
}
