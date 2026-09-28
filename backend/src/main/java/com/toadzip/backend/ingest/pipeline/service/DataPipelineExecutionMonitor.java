package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineWorkProgress;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public final class DataPipelineExecutionMonitor {

    private final UUID executionId;
    private final DataPipelineExecutionStateService stateService;
    private final Clock clock;
    private String workLabel;
    private String workUnit;
    private long completedCount;
    private long totalCount;
    private Instant workStartedAt;

    public DataPipelineExecutionMonitor(
            UUID executionId, DataPipelineExecutionStateService stateService, Clock clock
    ) {
        this.executionId = executionId;
        this.stateService = stateService;
        this.clock = clock;
    }

    public synchronized void beginWork(String label, String unit, long total) {
        workLabel = label;
        workUnit = unit;
        completedCount = 0;
        totalCount = total;
        workStartedAt = clock.instant();
        publishWork();
    }

    public synchronized void workCompleted() {
        if (workStartedAt == null) {
            return;
        }
        completedCount++;
        publishWork();
    }

    public synchronized void pageCompleted(int page, int totalRows, int pageSize) {
        if (workStartedAt == null) {
            return;
        }
        completedCount = page;
        totalCount = -1;
        if (totalRows >= 0) {
            totalCount = Math.max(1, (totalRows + (long) pageSize - 1) / pageSize);
        }
        publishWork();
    }

    private void publishWork() {
        stateService.recordWorkProgress(executionId, new DataPipelineWorkProgress(
                workLabel, workUnit, completedCount, totalCount, workStartedAt, clock.instant()
        ));
    }

    public void checkStopRequested() {
        if (stateService.isStopRequested(executionId)) {
            throw new DataPipelineStoppedException();
        }
    }

    public void requestStarted(String description) {
        stateService.recordRequestStarted(
                executionId, description.substring(0, Math.min(description.length(), 500)), clock.instant()
        );
    }

    public void requestFinished() {
        stateService.recordRequestFinished(executionId, clock.instant());
    }
}
