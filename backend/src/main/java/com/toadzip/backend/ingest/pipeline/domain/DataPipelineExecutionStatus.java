package com.toadzip.backend.ingest.pipeline.domain;

public enum DataPipelineExecutionStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    COMPLETED_WARNINGS,
    COMPLETED_WITH_SKIPS,
    FAILED,
    STOPPED;

    /** 받을 것을 모두 받은 완료. 호출 제한으로 건너뛴 완료는 다 받지 못했으므로 넣지 않는다. */
    public boolean isSuccessful() {
        return this == COMPLETED || this == COMPLETED_WARNINGS;
    }

    public boolean isFinished() {
        return this != IDLE && this != RUNNING;
    }
}
