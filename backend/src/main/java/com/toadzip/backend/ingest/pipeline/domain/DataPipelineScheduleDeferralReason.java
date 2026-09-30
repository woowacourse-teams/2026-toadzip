package com.toadzip.backend.ingest.pipeline.domain;

public enum DataPipelineScheduleDeferralReason {
    EXECUTION_IN_PROGRESS("execution_in_progress"),
    COLLECTION_NOT_COMPLETED("collection_not_completed");

    private final String metricValue;

    DataPipelineScheduleDeferralReason(String metricValue) {
        this.metricValue = metricValue;
    }

    public String metricValue() {
        return metricValue;
    }
}
