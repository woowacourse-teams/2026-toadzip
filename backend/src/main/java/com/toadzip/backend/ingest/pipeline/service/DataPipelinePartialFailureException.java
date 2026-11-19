package com.toadzip.backend.ingest.pipeline.service;

import com.toadzip.backend.ingest.pipeline.domain.DataPipelineFailureCause;
import com.toadzip.backend.ingest.pipeline.domain.DataPipelineStep;

class DataPipelinePartialFailureException extends RuntimeException {

    private final DataPipelineStep step;

    private final String serverResponse;

    /** 실패한 단계 중 하나가 성공한 대상 없이 통째로 실패했다. */
    private final boolean failedEntirely;

    DataPipelinePartialFailureException(DataPipelineStep step, String serverResponse) {
        this(step, serverResponse, false);
    }

    DataPipelinePartialFailureException(DataPipelineStep step, String serverResponse, boolean failedEntirely) {
        super(step.displayName() + " 단계가 일부 실패했습니다.");
        this.step = step;
        this.serverResponse = serverResponse;
        this.failedEntirely = failedEntirely;
    }

    /** 실패 원인. 대상이 모두 실패한 단계가 있으면 일부 실패와 구별한다. */
    DataPipelineFailureCause cause() {
        if (failedEntirely) {
            return DataPipelineFailureCause.ALL_TARGETS_FAILED;
        }
        return DataPipelineFailureCause.PARTIAL;
    }

    DataPipelineStep getStep() {
        return step;
    }

    String getServerResponse() {
        return serverResponse;
    }

}
