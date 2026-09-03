package com.toadzip.backend.ingest.domain;

public enum DataPipelineExecutionStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_SKIPS,
    FAILED
}
