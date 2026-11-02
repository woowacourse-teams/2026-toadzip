package com.toadzip.backend.ingest.collection.myhome.announcement.service;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.api.MyHomeAnnouncementCollectionCommand;
import com.toadzip.backend.ingest.collection.paging.service.PagedSourceCollector;
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
}
