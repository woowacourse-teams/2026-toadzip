package com.toadzip.backend.ingest.pipeline.domain;

public enum DataPipelineScheduleStage {
    COLLECTION("collection"),
    REFINEMENT("refinement");

    private final String metricValue;

    DataPipelineScheduleStage(String metricValue) {
        this.metricValue = metricValue;
    }

    public String metricValue() {
        return metricValue;
    }
}
