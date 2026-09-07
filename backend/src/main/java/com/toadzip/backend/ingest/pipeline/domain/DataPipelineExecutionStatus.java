package com.toadzip.backend.ingest.pipeline.domain;

public enum DataPipelineExecutionStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_SKIPS,
    FAILED
}
