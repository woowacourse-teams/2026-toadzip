package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;

class DataPipelinePartialFailureException extends RuntimeException {

    private final DataPipelineStep step;

    private final String serverResponse;

    private final boolean hasLhRateLimit;

    DataPipelinePartialFailureException(DataPipelineStep step, String serverResponse) {
        this(step, serverResponse, false);
    }

    DataPipelinePartialFailureException(DataPipelineStep step, String serverResponse, boolean hasLhRateLimit) {
        super(step.displayName() + " 단계가 일부 실패했습니다.");
        this.step = step;
        this.serverResponse = serverResponse;
        this.hasLhRateLimit = hasLhRateLimit;
    }

    DataPipelineStep getStep() {
        return step;
    }

    String getServerResponse() {
        return serverResponse;
    }

    boolean hasLhRateLimit() {
        return hasLhRateLimit;
    }
}
