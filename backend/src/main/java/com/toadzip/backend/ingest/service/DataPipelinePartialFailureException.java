package com.toadzip.backend.ingest.service;

import com.toadzip.backend.ingest.domain.DataPipelineStep;

class DataPipelinePartialFailureException extends RuntimeException {

    private final DataPipelineStep step;

    private final Object serverResponse;

    DataPipelinePartialFailureException(DataPipelineStep step, Object serverResponse) {
        super(step.displayName() + " 단계가 일부 실패했습니다.");
        this.step = step;
        this.serverResponse = serverResponse;
    }

    DataPipelineStep getStep() {
        return step;
    }

    Object getServerResponse() {
        return serverResponse;
    }
}
